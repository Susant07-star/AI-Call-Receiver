import WebSocket from 'ws';

console.log('Starting mock Twilio client...');

const ws = new WebSocket('ws://127.0.0.1:8080/media-stream');

ws.on('open', () => {
    console.log('✅ Connected to local server.');

    // 1. Send the Twilio "start" event
    const startPayload = {
        event: 'start',
        sequenceNumber: '1',
        start: {
            streamSid: 'MZmock_stream_123',
            accountSid: 'ACmock',
            callSid: 'CAmock',
            tracks: ['inbound'],
            customParameters: {}
        },
        streamSid: 'MZmock_stream_123'
    };

    console.log('📤 Sending Twilio "start" event...');
    ws.send(JSON.stringify(startPayload));
});

ws.on('message', (data) => {
    const msg = JSON.parse(data.toString());
    
    if (msg.event === 'media') {
        const payloadLength = msg.media.payload.length;
        console.log(`✅ SUCCESS! Received AI audio from server! (event: media, payload size: ${payloadLength} bytes)`);
        
        // We received audio! The AI spoke successfully. 
        // We can close the connection now.
        console.log('Closing mock call.');
        ws.close();
    } else {
        console.log('Received unknown event:', msg.event);
    }
});

ws.on('close', () => {
    console.log('🔌 Mock Twilio disconnected.');
    process.exit(0);
});

ws.on('error', (err) => {
    console.error('❌ WebSocket error:', err);
});
