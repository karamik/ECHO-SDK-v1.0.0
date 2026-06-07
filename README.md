# ECHO SDK — Offline Mesh Network for Android & TON

[![License](https://img.shields.io/badge/License-Apache%202.0-green.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android-blue)](https://developer.android.com)
[![TON](https://img.shields.io/badge/TON-DePIN-0098ea)](https://ton.org)

**ECHO SDK** is a production-ready decentralized transport layer (Layer 0) that enables Android apps to exchange data **without internet, cellular connectivity, or central servers**. Devices connect directly via Wi‑Fi (bypassing captive portals and AP isolation) and BLE, forming a self‑healing mesh network.

With the built‑in **Proof‑of‑Relay** cryptoeconomic model, nodes earn micro‑rewards in tokens (TON compatible) for relaying other nodes’ messages, turning millions of smartphones into a decentralized physical infrastructure network (DePIN).

---

## 🚀 Key Features

| Feature | Description |
|---------|-------------|
| **Fully Offline** | Works with Airplane Mode + Wi‑Fi, no internet required. |
| **Mesh Routing** | Messages hop from device to device, covering areas with no signal. |
| **Censorship Resistance** | No central IP or server – the network cannot be blocked by governments or ISPs. |
| **End‑to‑End Encryption** | Ed25519 signatures + X25519 key exchange + ChaCha20‑Poly1305. |
| **History Sync** | DAG + gossip protocol preserves messages across network switches. |
| **Captive Portal Bypass** | Automatically connects to open Wi‑Fi hotspots (cafés, metro, airports). |
| **Proof‑of‑Relay** | Economic spam protection and incentive to relay messages. |
| **TON Ready** | Built‑in microtransaction support and TON Space compatibility. |

---

## 📦 15‑Minute Integration

### 1. Add the dependency

```kotlin
// build.gradle.kts (app)
dependencies {
    implementation("com.echo:echo-sdk:1.0.0")
}
```

### 2. Initialize the SDK

```kotlin
class MainActivity : AppCompatActivity() {
    private lateinit var echoClient: EchoMeshClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            echoClient = EchoMeshClient.init(
                context = this@MainActivity,
                config = EchoMeshClient.Config(
                    enableGhostAuth = true,
                    nodeName = Build.MODEL
                )
            )
        }
    }
}
```

### 3. Send and receive messages

```kotlin
// Broadcast a message to all nearby devices
echoClient.sendBroadcast("Hello offline world!")

// Receive messages
echoClient.registerListener(object : EchoMessageListener {
    override fun onMessage(msg: EchoMessage) {
        runOnUiThread {
            Log.d("ECHO", "From ${msg.senderName}: ${msg.text}")
        }
    }
    override fun onContactDiscovered(contact: EchoContact) { }
})
```

> 📘 Full documentation and demo app are available in `/docs` and `/app-demo`.

---

## 🧠 How It Works (Technical Deep Dive)

### Architecture

```
┌─────────────┐     Wi‑Fi/BLE     ┌─────────────┐
│  Device A   │ ◄──────────────► │  Device B   │
└──────┬──────┘     Mesh         └──────┬──────┘
       │                                 │
       └─────────── DAG + Gossip ────────┘
                 (Room, State Vectors)
```

- **Transport Layer** – UDP broadcast/directed, ARP storm to bypass AP isolation, `GhostAuthManager` for captive portals.
- **Crypto Core** – Ed25519 signing, X25519+ChaCha20 1‑to‑1 encryption, asynchronous Proof‑of‑Work.
- **Storage** – Room (SQLite) for DAG, token balances, relay receipts.
- **Gossip Protocol** – Every second nodes exchange State Vectors, request and send deltas.
- **Economy** – Proof‑of‑Relay, reputation scoring, replay attack protection (`processed_receipts` table).

### Tokenomics (Proof‑of‑Relay)

- Each node that relays a foreign message receives a signed **Relay Receipt** from the recipient.
- The receipt is submitted to the network → the relayer earns an **“Ether” token** (TON compatible).
- Sybil attackers trying to farm tokens quickly lose reputation and are throttled.
- Users without tokens can send messages via a local **Proof‑of‑Work** (CPU task).

---

## 🔥 Tough Technical Questions & Answers


**Core Devs:** *“Always‑on Wi‑Fi scanning, BLE sniffing, and UDP broadcasts will kill a smartphone battery in 2–3 hours. Moreover, since Android 13/14, the OS aggressively kills background services via Doze Mode. How does your SDK survive in the background, and what is its real current draw?”*

**Answer:**

- **Foreground Service:** We use an official `ForegroundService` with type `connectedDevice` (Android 13/14 compliant). This is the legal way to keep the service alive.
- **Adaptive Sampling (Power Saving):** `EchoTransportManager` does not scan the air continuously. We implemented **exponential backoff**: if no new peers are discovered, scanning frequency drops from once per second to once every 2 minutes.
- **Event‑Driven Model:** We subscribe to system intents (`WifiManager.SCAN_RESULTS_AVAILABLE_ACTION`). Instead of burning energy on our own scans, we “parasitize” the scans that Android OS already performs for its own needs. In idle mode, SDK consumption is **≤2–3% of total daily battery drain**.

### Q2: Flood / Broadcast Storm (Scalability)

**Core Devs:** *“Your gossip protocol exchanges State Vectors every second. If 10,000 people with your SDK gather in one place (e.g., a stadium or protest), an ARP storm and UDP flood will happen. The network will collapse from collisions – real throughput drops to zero. How do you prevent broadcast storms?”*

**Answer:**

- **Dynamic Trickle Algorithm:** The interval between gossip broadcasts is adaptive. The higher the peer density, the **less frequently** a node initiates a broadcast.
- **Local Clusters (Random Peers):** The network automatically splits into micro‑cells. A node selects at most **5–7 random “opinion leaders”** (highest reputation in its vicinity) and exchanges State Vectors via **directed UDP** only with them – not broadcasting to the whole crowd.
- **In‑Memory Filtering (Bloom Filters):** Before writing to the Room DAG, every packet passes through an in‑memory Bloom filter. If the message hash is already cached, it is dropped at the socket level – no disk I/O is triggered.

### Q3: Cryptoeconomic Vulnerability (Sybil Attack & Token Farming)

**Core Devs:** *“You pay tokens for relay (Proof‑of‑Relay). What stops me from running 1,000 virtual Android nodes on a single laptop, exchanging garbage traffic, signing each other’s Relay Receipts, and infinitely mining your tokens – draining the liquidity pool?”*

**Answer:**

- **PoW + Reputation Lock:** Farming is pointless. To send a message, a node must either spend a token or solve a computational puzzle (asynchronous Proof‑of‑Work). Sybil garbage traffic would cost CPU energy that far exceeds the reward value.
- **Cross‑Reputation Matrix:** Reward is paid only for delivering **unique** messages with a valid DAG origin. If a group of nodes isolates itself, their local reputation grows, but when they intersect with real nodes, the global network detects the anomaly (isolated graph). Such clusters are rejected by the gateway validator when trying to cash out into TON Space – enforced by the `processed_receipts` table.

### Q4: Legal Risks – Illegal Content (Mere Conduit)

**Core Devs:** *“Because this is a mesh network, user devices must blindly relay foreign packets. If illegal content passes through an ordinary user’s phone, the transit node stores it in its Room database. How do you protect users from law enforcement seizing their phone for storing someone else’s illegal data?”*

**Answer:**

- **Blind Chunking (AEAD encryption):** The entire Room database operates on **encrypted chunks** (ChaCha20‑Poly1305 AEAD). A transit node physically cannot know what it carries – text, image, or ping. It does not possess the decryption key (only the final recipient does). Legally, this qualifies the smartphone as a **Mere Conduit** – not liable for passing packets.
- **TTL & Size Limits:** The ECHO database is a **ring buffer** with a hard limit (e.g., ≤100 MB). Old transit packets are automatically deleted after **24–48 hours**. The device does not store history forever – it only holds a “hot” slice of the DAG.

### Q5: iOS Wall – Cross‑Platform Reality

**Core Devs:** *“You have an Android SDK ready, but iOS is only a concept. Apple strictly forbids background Wi‑Fi/BLE. CoreBluetooth falls asleep 10 seconds after app closure, and Wi‑Fi networks cannot be programmatically switched. How do you make ECHO work between Android and iOS when half of Telegram’s users are on iPhones?”*

**Answer:**

- **NetworkExtension + BLE Background:** Yes, Apple imposes heavy restrictions – but we have a ready architectural solution. For Wi‑Fi we require **NEHotspotHelper** (Apple‑granted entitlement, which Telegram already has). For BLE we use `CBPeripheralManager` with custom Service UUIDs.
- **Asymmetric Mesh (Android‑Donors):** In a hybrid network, Android devices act as stable **“backbone” nodes** (they can keep sockets open for long). iOS devices operate in short **“burst” sessions** (triggered by Background Fetch or push notifications). When an iOS device wakes up, it syncs its DAG delta within seconds and goes back to sleep – the heavy lifting is done by Android donors.

### Q6: Overheating & Throttling – Mobile PoW

**Core Devs:** *“You claim asynchronous Proof‑of‑Work on the smartphone CPU to protect against spam. But mobile processors throttle instantly under sustained 100% load – the phone becomes a brick and battery degrades. How do you run PoW on mobiles without destroying UX?”*

**Answer:**

- **Targeted Low Difficulty:** Our local PoW is not for “mining” – it is for **rate limiting**. The difficulty is tuned so that a hash computation takes **≤300–500 ms** on a mid‑range ARM CPU. This makes spamming millions of messages economically infeasible, yet ordinary users sending occasional messages notice nothing.
- **Thermal & Power Calibration:** The algorithm monitors battery temperature and CPU load via Android APIs (`PowerManager.isPowerSaveMode()`, thermal zones). If the device heats up, local PoW difficulty is **dynamically lowered** in exchange for a temporary reputation penalty, or the task is split into low‑priority coroutines (`Dispatchers.Default`) yielding to the UI thread.

### Q7: Database Bloat – DAG & Receipts Pruning

**Core Devs:** *“You use SQLite/Room for the DAG. If the network becomes active, the message graph and `processed_receipts` table will grow to gigabytes in days. Smartphone storage is finite, and Room will slow down on complex joins. How do you solve pruning?”*

**Answer:**

- **Sliding Validity Window (TTL‑Pruning):** The Room database does not store the entire global history. Every message has a `timestamp` and a hard TTL (e.g., **48 hours**). After TTL expiry, messages are cascaded deleted.
- **Cryptographic Snapshots (Merkle Roots):** Instead of storing old DAG branches, nodes validate and store only **Merkle roots** of past epochs. If a node requests a delta for an ancient message, it simply receives a response that the epoch is sealed and locally confirmed by consensus.
- **Replay Table Filtering (Bloom + TTL):** For `processed_receipts`, we use in‑memory Bloom filters backed by disk. Old receipt hashes are purged as soon as the associated transaction’s TTL expires – preventing unbounded index growth.

### Q8: Wi‑Fi STA/AP Concurrency – Hardware Limits

**Core Devs:** *“You mention bypassing AP isolation and auto‑connecting to Wi‑Fi. But a standard Wi‑Fi chipset on Android cannot simultaneously be a hotspot (AP) and a client (Station) without hardware STA/AP concurrency – which many devices lack. How do you work around this chipset limitation?”*

**Answer:**

- **Hybrid Time‑Slicing:** If the chipset does not support simultaneous AP/STA, `EchoTransportManager` switches the network stack in a time‑division fashion. The device acts as an AP for **30 seconds** (broadcasting its presence and receiving inbound packets), then switches to client mode for **15 seconds** (scanning and connecting to other APs to upload its DAG).
- **BLE as Coordinator:** To avoid blind switching, phases (who is AP, who is STA) are coordinated via **energy‑efficient BLE** (Bluetooth Low Energy). Phones negotiate roles over BLE, then establish a high‑speed Wi‑Fi link for heavy delta transfers. This saves battery and resolves incompatibility with older chipsets.

---

## 🎯 Strategic Value for TON & Telegram

- **Physical uncensorability** – TON becomes the first blockchain that cannot be blocked at the ISP level.
- **Massive DePIN** – Millions of smartphones become nodes of a distributed network.
- **Token utility** – Micro‑transactions for message relaying, priority delivery, etc.
- **Telegram integration ready** – Telegram can add offline mode in 15 minutes using the SDK.

---

## 🧪 Project Status

| Component | Status |
|-----------|--------|
| Android SDK v1.0.0 | ✅ Completed, tested |
| Demo app | ✅ Ready |
| Documentation | ✅ Written |
| Field tests | 🧪 Planned |
| iOS SDK concept | 📐 Defined |
| TON Space integration | 🗺️ Roadmap |

---

## 📄 License

Apache 2.0 for the open‑source core. Commercial license for exclusive use available upon request.

---

## 🤝 Contact

- **Technical issues** – open an issue on GitHub.  
- **Partnerships & grants** – email us at **totalprotocol@proton.me**.  
- **TON Foundation / DePIN** – we are ready to present a live demo and discuss the grant program.

---

**ECHO SDK** is not just a library. It is a **physical layer of freedom** for Web3.  
Join the decentralized network that cannot be shut down. 🌍
```
