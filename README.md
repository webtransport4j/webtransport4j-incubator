# webtransport4j-incubator

# Local Development Guide

Follow these steps to run `webtransport4j` locally with a trusted self-signed certificate and a secure browser connection.

## 1. Generate Certificates (mkcert)

WebTransport requires HTTPS. We use `mkcert` to create a locally trusted certificate.

1. **Install mkcert:**
```bash
brew install mkcert
brew install nss  # Only needed if you use Firefox

```


2. **Initialize Root CA:**
```bash
mkcert -install

```


3. **Generate Certs:**
Run this in your **Documents** folder to match the Java config below.
```bash
cd ~/Documents
mkcert localhost

```


*Output:* `localhost.pem` and `localhost-key.pem`

---

## 2. Server Setup (Java)

Configure your Netty/Java server to use the generated certificates.

**Code Snippet:**

```java
QuicSslContext sslContext = QuicSslContextBuilder.forServer(
        new File("/Users/<username>/Documents/localhost-key.pem"), // Private Key
        null,
        new File("/Users/<username>/Documents/localhost.pem"))     // Public Cert
    .applicationProtocols(Http3.supportedApplicationProtocols())
    .build();

```

---

## 3. Client Setup (HTML)

Create an `index.html` file in your project folder to test the connection.

**File:** `index.html`

```html
<!DOCTYPE html>
<body>
    <h2>Status: <span id="status">Disconnected</span></h2>
    <script>
        async function init() {
            const status = document.getElementById('status');
            try {
                // Port 4433 must match your Java server port
                const transport = new WebTransport('https://localhost:4433/webtransport');
                status.textContent = 'Connecting...';
                
                await transport.ready;
                status.textContent = 'Connected!';
                console.log('Handshake successful');

                const reader = transport.datagrams.readable.getReader();
                while (true) {
                    const { value, done } = await reader.read();
                    if (done) break;
                    console.log('Received:', new TextDecoder().decode(value));
                }
            } catch (e) {
                status.textContent = 'Error: ' + e;
            }
        }
        init();
    </script>
</body>

```

**Serve the Client:**
Open a terminal in the folder containing `index.html` and run:

```bash
python3 -m http.server 8000

```

*Client URL: `http://localhost:8000*`

---

## 4. Run Chrome (Dev Mode)

You must launch Chrome with specific flags to force QUIC on localhost and ignore certificate mismatches.

**Close all Chrome windows first**, then run this command in Terminal:

```bash
/Applications/Google\ Chrome.app/Contents/MacOS/Google\ Chrome \
  --origin-to-force-quic-on=localhost:4433 \
  --ignore-certificate-errors \
  --user-data-dir=/tmp/chrome-dev-profile

```
Enable webtransport in chrome://flags/

<img width="783" height="359" alt="Screenshot 2026-01-21 at 11 28 40 AM" src="https://github.com/user-attachments/assets/7fd2e45a-cc03-4aa8-a705-dfdb2d273e0f" />

Relaunch your chrome

**Verification:**

1. Navigate to `http://localhost:8000`.
2. Open DevTools (**Cmd+Option+I**) -> **Network** tab.
3. Look for the connection request.
4. **Protocol** column should say **`h3`** (HTTP/3).

**Use this in chrome console to test webtrasnport all uni/bi/datagram apis**

```bash
// 1. Connect (No hash needed with mkcert!)
const transport = new WebTransport("https://localhost:4433”);

// Helper to decode server responses
const textDecoder = new TextDecoder();
const textEncoder = new TextEncoder();

// --- SETUP LISTENERS (To see what the server sends back) ---

// A. Listen for Incoming Datagrams
(async () => {
  const reader = transport.datagrams.readable.getReader();
  console.log("👂 Listening for Datagrams...");
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    console.log("Received Datagram:", textDecoder.decode(value));
  }
})();

// B. Listen for Incoming Unidirectional Streams
(async () => {
  const reader = transport.incomingUnidirectionalStreams.getReader();
  console.log("👂 Listening for Uni Streams...");
  while (true) {
    const { value: stream, done } = await reader.read();
    if (done) break;
    readStream(stream, "Uni");
  }
})();

// C. Listen for Incoming Bidirectional Streams
(async () => {
  const reader = transport.incomingBidirectionalStreams.getReader();
  console.log("👂 Listening for Bi Streams...");
  while (true) {
    const { value: stream, done } = await reader.read();
    if (done) break;
    readStream(stream.readable, "Bi-Incoming");
  }
})();

// Helper to read data from a stream
async function readStream(readableStream, type) {
  const reader = readableStream.getReader();
  console.log(`OPENED: ${type} Stream`);
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    console.log(`Received [${type}]:`, textDecoder.decode(value));
  }
  console.log(`CLOSED: ${type} Stream`);
}

// --- ACTIVE TESTS (Send Data) ---

try {
  console.log("⏳ Waiting for connection...");
  await transport.ready;
  console.log("✅ TRANSPORT READY!");

  // TEST 1: Send Datagram (Fire and forget)
  console.log("🚀 sending Datagram...");
  const datagramWriter = transport.datagrams.writable.getWriter();
  datagramWriter.write(textEncoder.encode("Hello Datagram! 📦"));
  datagramWriter.releaseLock();

  // TEST 2: Create Unidirectional Stream (Send only)
  console.log("🚀 opening Uni Stream...");
  const uniStream = await transport.createUnidirectionalStream();
  const uniWriter = uniStream.getWriter();
  await uniWriter.write(textEncoder.encode("Hello Unidirectional Stream! ➡️"));
  await uniWriter.close(); // Important to close stream so server knows we are done

  // TEST 3: Create Bidirectional Stream (Send and Request Reply)
  console.log("🚀 opening Bi Stream...");
  const biStream = await transport.createBidirectionalStream();
  const biWriter = biStream.writable.getWriter();
  await biWriter.write(textEncoder.encode("Hello Bidirectional! ↔️"));
  //await biWriter.close();
  
  // Read the reply from the server for this specific stream
  readStream(biStream.readable, "Bi-Reply");

} catch (e) {
  console.error("❌ Connection failed:", e);
}
```
