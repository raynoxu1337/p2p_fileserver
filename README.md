# P2P File Server for Android
Turn your old android phone into a self-hosted server with no static IP or TLD needed, can traverse most NAT's. Basically this is just a convenient wrapper for non technical users to turn old ewaste phones into a proper file server that you can access with a share link/token on almost any browser and 0 hosting costs, full privace and encrypted traffic.

## Usage
* Install APK on your old android phone
* Follow in-app setup to give storage and wake permissions to the app.
* setup your admin user name and password (make sure it's a properly secure one, don't use "ASSWORD123" for password).
* You will get a QR code, a link and a token. you can use the link, QR normally or paste the token into [Resolver Website](https://raynoxu1337.github.io/p2p_fileserver/).
* Login with your credentials in the login page, when loged in you can add manage files, add other users or create shareable links just like in cloud storage solutions.
* Imagine the server token (or qr/link) is just an invite link to KNOW that the server exists, QR/link/token does not give access to files, you need to create your friends an account from your admin panel once you are logged in yourself. For more info look [here](https://github.com/gtsteffaniak/filebrowser), but the UI is quite manageable to figure out.

DISCLAIMER:
This android wrapper was vibecoded with Claude, tho all the components are battletested and this is just a trivial glue code so I don't expect many issues here. All the expected things work and theres not much that can go wrong here IMO.
## For nerds
### Self-host relay
Build web/ with node (can use build-web.sh) and upload build products to a publicly acceessible server OR static server like neocities. Tested and works.
### Build APK
Run build scripts build-native.sh and build-apk.sh, you need NDK,JDK and go installed, I built it on debian 13.
## Componets used
| Part | What it does |
|---|---|
| [FileBrowser Quantum](https://github.com/gtsteffaniak/filebrowser) `v2.0.2-stable` | The file manager web UI and API. It runs on the phone and listens on `127.0.0.1` only, so it isn't directly reachable on your network. Visitors reach it through the p2p node, directly on the same Wi-Fi or over the internet via relays, behind FileBrowser's login. |
| [go-libp2p](https://github.com/libp2p/go-libp2p) + IPFS Amino DHT + public circuit relays | Gives the phone a permanent ID and makes it findable and reachable behind NAT/CGNAT. The p2p node listens on port **4001** (TCP and UDP, on all network interfaces; change it under **Advanced → P2P port**), so devices on the same network can connect to it. It also asks your router to forward that port via UPnP / NAT-PMP, which opens it to the internet if the router allows that. |
| [pion/webrtc](https://github.com/pion/webrtc) | Direct, encrypted browser↔phone data link that carries the HTTP traffic. |
| Kotlin / Jetpack Compose app | Start/stop, settings, share link + QR code, foreground service. |
| Static resolver page (`web/p2p_resolver/`) | Hosted for free on Neocities or GitHub Pages. A generic, app-agnostic P2P→HTTP resolver: it finds any server by PeerID and shows the web app that server exposes. No tokens, keys or app-specific settings are built in. |
