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

**Verification:**

1. Navigate to `http://localhost:8000`.
2. Open DevTools (**Cmd+Option+I**) -> **Network** tab.
3. Look for the connection request.
4. **Protocol** column should say **`h3`** (HTTP/3).
