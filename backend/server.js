import Fastify from 'fastify';
import fastifyWebsocket from '@fastify/websocket';
import fastifyFormbody from '@fastify/formbody';
import pkg from 'wavefile';
const { WaveFile } = pkg;
import dotenv from 'dotenv';
import TelegramBot from 'node-telegram-bot-api';
import { GoogleGenAI, Modality } from '@google/genai';

dotenv.config();

const fastify = Fastify({ logger: true });
fastify.register(fastifyWebsocket);
fastify.register(fastifyFormbody);

// API Key rotation
const GEMINI_API_KEYS = process.env.GEMINI_API_KEYS ? process.env.GEMINI_API_KEYS.split(',').map(k => k.trim()) : [];
let currentKeyIndex = 0;
function getNextApiKey() {
    if (GEMINI_API_KEYS.length === 0) return null;
    const key = GEMINI_API_KEYS[currentKeyIndex];
    currentKeyIndex = (currentKeyIndex + 1) % GEMINI_API_KEYS.length;
    return key;
}

const TELEGRAM_BOT_TOKEN = process.env.TELEGRAM_BOT_TOKEN;
const TELEGRAM_CHAT_ID = process.env.TELEGRAM_CHAT_ID;
const bot = TELEGRAM_BOT_TOKEN ? new TelegramBot(TELEGRAM_BOT_TOKEN, { polling: false }) : null;

// Keep-alive route for free hosting platforms
fastify.get('/ping', async (request, reply) => {
    return { status: 'alive' };
});

// Twilio Webhook: answer the call and open a media stream
fastify.all('/incoming-call', async (request, reply) => {
    const host = request.headers.host;
    const twiml = `<?xml version="1.0" encoding="UTF-8"?>
<Response>
    <Say>Please wait while I connect you to the AI assistant.</Say>
    <Connect>
        <Stream url="wss://${host}/media-stream" />
    </Connect>
</Response>`;
    reply.type('text/xml').send(twiml);
});

// WebSocket Route: bridges Twilio audio <-> Gemini Live SDK
fastify.register(async function (fastify) {
    fastify.get('/media-stream', { websocket: true }, async (connection, req) => {
        // In @fastify/websocket v8+ (Fastify v5), 'connection' IS the WebSocket object directly
        const twilioWs = connection.socket ?? connection;
        let streamSid = null;
        let transcript = [];
        let geminiSession = null;
        let greetingSent = false;

        console.log('[Call] Twilio connected. Waiting for stream start...');

        // Handle incoming audio from Twilio
        twilioWs.on('message', async (message) => {
            try {
                const data = JSON.parse(message);

                if (data.event === 'start') {
                    streamSid = data.start.streamSid;
                    console.log(`[Twilio] Stream started: ${streamSid}`);

                    // NOW start the Gemini session
                    try {
                        const apiKey = getNextApiKey();
                        const ai = new GoogleGenAI({ apiKey });
                        
                        geminiSession = await ai.live.connect({
                            model: 'models/gemini-3.1-flash-live-preview',
                            config: {
                                responseModalities: [Modality.AUDIO],
                                generationConfig: {
                                    speechConfig: {
                                        voiceConfig: {
                                            prebuiltVoiceConfig: {
                                                voiceName: 'Aoede'
                                            }
                                        }
                                    }
                                },
                                systemInstruction: {
                                    parts: [{
                                        text: 'You are an AI phone assistant answering a call on behalf of a user who is unavailable. Be friendly and professional. Greet the caller, ask for their name, and find out why they are calling. Keep responses short and conversational. Do not use markdown or lists.'
                                    }]
                                }
                            },
                            callbacks: {
                                onopen: () => {
                                    console.log('[Gemini] Session opened.');
                                },
                                onmessage: (message) => {
                                    // Send greeting only after setupComplete handshake
                                    if (message.setupComplete && !greetingSent) {
                                        greetingSent = true;
                                        console.log('[Gemini] Setup complete. Sending greeting...');
                                        geminiSession.sendClientContent({
                                            turns: [{ role: 'user', parts: [{ text: 'The phone call has just connected. Please greet the caller now.' }] }],
                                            turnComplete: true
                                        });
                                    }
                                    try {
                                        // Collect transcript
                                        if (message.serverContent?.outputTranscription?.text) {
                                            const txt = message.serverContent.outputTranscription.text;
                                            transcript.push(`AI: ${txt}`);
                                            console.log(`[Gemini] Transcript: ${txt}`);
                                        }

                                        // Forward audio to Twilio
                                        if (message.serverContent?.modelTurn?.parts) {
                                            for (const part of message.serverContent.modelTurn.parts) {
                                                if (part.inlineData?.mimeType?.startsWith('audio/pcm')) {
                                                    const pcmBuffer = Buffer.from(part.inlineData.data, 'base64');
                                                    console.log(`[Gemini] Received audio chunk: ${pcmBuffer.length} bytes`);

                                                    // Gemini sends raw 16-bit signed LE PCM at 24kHz
                                                    // wavefile needs Int16Array, NOT a raw Buffer
                                                    const int16Samples = new Int16Array(
                                                        pcmBuffer.buffer,
                                                        pcmBuffer.byteOffset,
                                                        pcmBuffer.length / 2
                                                    );

                                                    // Convert 24kHz PCM → 8kHz mu-law for Twilio
                                                    const wav = new WaveFile();
                                                    wav.fromScratch(1, 24000, '16', int16Samples);
                                                    wav.toSampleRate(8000);
                                                    wav.toMuLaw();
                                                    const muLawBuffer = Buffer.from(wav.data.samples);

                                                    if (streamSid && twilioWs.readyState === 1) {
                                                        twilioWs.send(JSON.stringify({
                                                            event: 'media',
                                                            streamSid,
                                                            media: { payload: muLawBuffer.toString('base64') }
                                                        }));
                                                    }
                                                }
                                            }
                                        }
                                    } catch (err) {
                                        console.error('[Gemini] Error handling message:', err);
                                    }
                                },
                                onerror: (err) => {
                                    console.error('[Gemini] Session error:', err);
                                },
                                onclose: (e) => {
                                    console.log(`[Gemini] Session closed: ${e.code} ${e.reason}`);
                                }
                            }
                        });

                    } catch (err) {
                        console.error('[Gemini] Failed to start session:', err);
                        twilioWs.close();
                        return;
                    }

                } else if (data.event === 'media') {
                    const muLawBuffer = Buffer.from(data.media.payload, 'base64');

                    // Convert 8kHz mu-law → 16kHz PCM for Gemini
                    // Mu-law bytes are Uint8Array which wavefile's '8m' format expects
                    const wav = new WaveFile();
                    wav.fromScratch(1, 8000, '8m', new Uint8Array(muLawBuffer));
                    wav.fromMuLaw();
                    wav.toSampleRate(16000);

                    // After conversion, samples are Int16; send the underlying buffer
                    const pcmBuffer = Buffer.from(wav.data.samples.buffer);

                    if (geminiSession) {
                        geminiSession.sendRealtimeInput({
                            audio: {
                                data: pcmBuffer.toString('base64'),
                                mimeType: 'audio/pcm;rate=16000'
                            }
                        });
                    }

                } else if (data.event === 'stop') {
                    console.log('[Twilio] Stream stopped.');
                    if (geminiSession) geminiSession.close();
                }
            } catch (err) {
                console.error('[Twilio] Error handling message:', err);
            }
        });

        // When call ends, send Telegram summary
        twilioWs.on('close', () => {
            console.log('[Call] Ended.');
            if (geminiSession) {
                try { geminiSession.close(); } catch (e) { /* already closed */ }
            }

            if (bot && TELEGRAM_CHAT_ID) {
                const summary = transcript.length > 0
                    ? `📞 *Call Summary*\n\n${transcript.join('\n')}`
                    : `📞 *Call ended* — No transcript captured (caller may have hung up immediately).`;
                bot.sendMessage(TELEGRAM_CHAT_ID, summary, { parse_mode: 'Markdown' })
                    .catch(err => console.error('[Telegram] Failed to send message:', err));
            }
        });
    });
});

const start = async () => {
    try {
        const port = process.env.PORT || 8080;
        await fastify.listen({ port, host: '0.0.0.0' });
        console.log(`Server listening on port ${port}`);
    } catch (err) {
        fastify.log.error(err);
        process.exit(1);
    }
};

start();
