import twilio from 'twilio';
import dotenv from 'dotenv';

dotenv.config();

const accountSid = process.env.TWILIO_ACCOUNT_SID;
const authToken = process.env.TWILIO_AUTH_TOKEN;
const webhookUrl = process.env.TWILIO_INCOMING_CALL_URL;

if (!accountSid || !authToken || !webhookUrl) {
    throw new Error(
        'Missing TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN, or TWILIO_INCOMING_CALL_URL environment variable.'
    );
}

const client = twilio(accountSid, authToken);

async function run() {
    try {
        const incomingNumbers = await client.incomingPhoneNumbers.list({limit: 1});
        if (incomingNumbers.length === 0) {
            console.log("Error: You haven't bought a Twilio number yet.");
            return;
        }
        const fromNumber = incomingNumbers[0].phoneNumber;

        const outgoingCallerIds = await client.outgoingCallerIds.list({limit: 1});
        if (outgoingCallerIds.length === 0) {
            console.log("Error: You haven't verified your personal cell phone number in Twilio yet.");
            return;
        }
        const toNumber = outgoingCallerIds[0].phoneNumber;

        console.log(`Calling your personal phone (${toNumber}) from your Twilio number (${fromNumber})...`);

        const call = await client.calls.create({
            url: webhookUrl,
            to: toNumber,
            from: fromNumber
        });
        console.log(`Call triggered! SID: ${call.sid}`);
    } catch (err) {
        console.error("Twilio Error:", err.message);
    }
}

run();
