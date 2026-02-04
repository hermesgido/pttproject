const express = require('express');
const http = require('http');
const { Server } = require('socket.io');
const cors = require('cors');
const mediasoup = require('mediasoup');
const jwt = require('jsonwebtoken');
const bcrypt = require('bcryptjs');
const { v4: uuidv4 } = require('uuid');
const storage = require('./storage');

const app = express();
app.use(cors());
app.use(express.json());
app.use('/admin', express.static(__dirname + '/admin'));

const server = http.createServer(app);
const io = new Server(server, {
  cors: {
    origin: "*",
    methods: ["GET", "POST"]
  },
  // Allow older Socket.IO Android client (engine.io v3) to connect
  allowEIO3: true
});

// Public IP announced to remote clients for ICE transport candidates
const ANNOUNCED_IP = process.env.ANNOUNCED_IP || '62.84.190.56';

// Store active rooms and users
const rooms = new Map(); // roomId -> PTTRoom
const peers = new Map(); // socket.id -> { roomId, transport, producer, consumer, userId, userName, deviceId, companyId, transports }
const presence = new Map(); // deviceId -> { online, lastSeen }

// Persistent data
let DATA = storage.load();
function saveData() { storage.save(DATA); }
function findCompany(id) { return DATA.companies.find(c => c.id === id); }
function findChannel(id) { return DATA.channels.find(c => c.id === id); }
function findDeviceByAccount(companyId, accountNumber) { return DATA.devices.find(d => d.companyId === companyId && d.accountNumber === accountNumber); }
function findDeviceById(id) { return DATA.devices.find(d => d.id === id); }
function isMember(channelId, deviceId) { return DATA.memberships.some(m => m.channelId === channelId && m.deviceId === deviceId); }

// Mediasoup workers
let worker;
let router;

async function createMediasoupWorker() {
  worker = await mediasoup.createWorker({
    logLevel: 'warn',
    rtcMinPort: parseInt(process.env.RTC_MIN_PORT || '40000', 10),
    rtcMaxPort: parseInt(process.env.RTC_MAX_PORT || '49999', 10)
  });

  router = await worker.createRouter({
    mediaCodecs: [
      {
        kind: 'audio',
        mimeType: 'audio/opus',
        clockRate: 48000,
        channels: 2,
        parameters: {
          useinbandfec: 1,
          usedtx: 1,
          stereo: 1,
          maxaveragebitrate: 32000
        }
      }
    ]
  });

  console.log('✅ Mediasoup worker and router created');
}

// Half-duplex PTT logic
class PTTRoom {
  constructor(roomId) {
    this.roomId = roomId;
    this.peers = new Map(); // socket.id -> peer info
    this.currentSpeaker = null; // socket.id of current speaker
  }

  addPeer(socketId, userId, userName) {
    this.peers.set(socketId, {
      id: userId,
      name: userName,
      isSpeaker: false,
      joinedAt: Date.now()
    });
  }

  removePeer(socketId) {
    if (this.currentSpeaker === socketId) {
      this.currentSpeaker = null;
    }
    this.peers.delete(socketId);
  }

  requestToSpeak(socketId) {
    if (!this.currentSpeaker) {
      this.currentSpeaker = socketId;
      const peer = this.peers.get(socketId);
      if (peer) peer.isSpeaker = true;
      return { granted: true, speakerId: socketId };
    } else {
      const currentPeer = this.peers.get(this.currentSpeaker);
      return { 
        granted: false, 
        currentSpeaker: currentPeer ? currentPeer.name : 'Unknown',
        speakerId: this.currentSpeaker
      };
    }
  }

  stopSpeaking(socketId) {
    if (this.currentSpeaker === socketId) {
      this.currentSpeaker = null;
      const peer = this.peers.get(socketId);
      if (peer) peer.isSpeaker = false;
      return true;
    }
    return false;
  }

  getSpeakerInfo() {
    if (!this.currentSpeaker) return null;
    const peer = this.peers.get(this.currentSpeaker);
    return peer ? { id: peer.id, name: peer.name } : null;
  }
}

// Initialize mediasoup
createMediasoupWorker().catch(console.error);

// REST: Companies
app.get('/v1/companies', (req, res) => {
  res.json(DATA.companies);
});

app.post('/v1/companies', (req, res) => {
  const { name, logoUrl } = req.body || {};
  if (!name) return res.status(400).json({ error: 'name required' });
  const id = uuidv4();
  const company = { id, name, logoUrl: logoUrl || null, createdAt: Date.now() };
  DATA.companies.push(company);
  saveData();
  res.json(company);
});

// Delete company and all its data
app.delete('/v1/companies/:companyId', (req, res) => {
  const { companyId } = req.params;
  const exists = findCompany(companyId);
  if (!exists) return res.status(404).json({ error: 'company not found' });
  DATA.devices.filter(d => d.companyId === companyId).forEach(d => presence.delete(d.id));
  DATA.memberships = DATA.memberships.filter(m => {
    const ch = findChannel(m.channelId);
    return !(ch && ch.companyId === companyId);
  });
  DATA.channels = DATA.channels.filter(c => c.companyId !== companyId);
  DATA.devices = DATA.devices.filter(d => d.companyId !== companyId);
  DATA.companies = DATA.companies.filter(c => c.id !== companyId);
  rooms.forEach((room, id) => {
    const ch = findChannel(id);
    if (ch && ch.companyId === companyId) rooms.delete(id);
  });
  saveData();
  res.json({ ok: true });
});

// REST: Devices
app.get('/v1/companies/:companyId/devices', (req, res) => {
  const { companyId } = req.params;
  res.json(DATA.devices.filter(d => d.companyId === companyId));
});

app.post('/v1/companies/:companyId/devices', async (req, res) => {
  const { companyId } = req.params;
  const { displayName, password } = req.body || {};
  const company = findCompany(companyId);
  if (!company) return res.status(404).json({ error: 'company not found' });
  if (!displayName || !password) return res.status(400).json({ error: 'displayName and password required' });
  const accountNumber = storage.generateAccountNumber(DATA.devices, companyId);
  const passwordHash = await bcrypt.hash(password, 10);
  const device = { id: uuidv4(), companyId, accountNumber, passwordHash, displayName, role: 'device', status: 'active', createdAt: Date.now() };
  DATA.devices.push(device);
  saveData();
  res.json({ id: device.id, companyId, accountNumber, displayName, status: device.status });
});

// Delete device and related memberships
app.delete('/v1/companies/:companyId/devices/:deviceId', (req, res) => {
  const { companyId, deviceId } = req.params;
  const device = findDeviceById(deviceId);
  if (!device || device.companyId !== companyId) return res.status(404).json({ error: 'device not found' });
  DATA.memberships = DATA.memberships.filter(m => m.deviceId !== deviceId);
  DATA.devices = DATA.devices.filter(d => d.id !== deviceId);
  presence.delete(deviceId);
  saveData();
  res.json({ ok: true });
});

// REST: Channels
app.get('/v1/companies/:companyId/channels', (req, res) => {
  const { companyId } = req.params;
  res.json(DATA.channels.filter(c => c.companyId === companyId));
});

app.post('/v1/companies/:companyId/channels', (req, res) => {
  const { companyId } = req.params;
  const { name } = req.body || {};
  const company = findCompany(companyId);
  if (!company) return res.status(404).json({ error: 'company not found' });
  if (!name) return res.status(400).json({ error: 'name required' });
  const channel = { id: uuidv4(), companyId, name, createdAt: Date.now() };
  DATA.channels.push(channel);
  saveData();
  res.json(channel);
});

// Delete channel and its memberships
app.delete('/v1/companies/:companyId/channels/:channelId', (req, res) => {
  const { companyId, channelId } = req.params;
  const channel = findChannel(channelId);
  if (!channel || channel.companyId !== companyId) return res.status(404).json({ error: 'channel not found' });
  DATA.memberships = DATA.memberships.filter(m => m.channelId !== channelId);
  DATA.channels = DATA.channels.filter(c => c.id !== channelId);
  rooms.delete(channelId);
  saveData();
  res.json({ ok: true });
});

// REST: Channel memberships
app.get('/v1/channels/:channelId/members', (req, res) => {
  const { channelId } = req.params;
  const members = DATA.memberships.filter(m => m.channelId === channelId).map(m => findDeviceById(m.deviceId)).filter(Boolean);
  res.json(members);
});

app.post('/v1/channels/:channelId/members', (req, res) => {
  const { channelId } = req.params;
  const { deviceId } = req.body || {};
  const channel = findChannel(channelId);
  if (!channel) return res.status(404).json({ error: 'channel not found' });
  const device = findDeviceById(deviceId);
  if (!device) return res.status(404).json({ error: 'device not found' });
  if (DATA.memberships.some(m => m.channelId === channelId && m.deviceId === deviceId)) return res.status(409).json({ error: 'already a member' });
  DATA.memberships.push({ channelId, deviceId });
  saveData();
  res.json({ ok: true });
});

app.delete('/v1/channels/:channelId/members/:deviceId', (req, res) => {
  const { channelId, deviceId } = req.params;
  DATA.memberships = DATA.memberships.filter(m => !(m.channelId === channelId && m.deviceId === deviceId));
  saveData();
  res.json({ ok: true });
});

// REST: Auth
app.post('/v1/auth/login', async (req, res) => {
  const { companyId, accountNumber, password } = req.body || {};
  const device = findDeviceByAccount(companyId, accountNumber);
  if (!device) return res.status(401).json({ error: 'invalid credentials' });
  const ok = await bcrypt.compare(password, device.passwordHash);
  if (!ok) return res.status(401).json({ error: 'invalid credentials' });
  const token = jwt.sign({ companyId, deviceId: device.id }, process.env.JWT_SECRET || 'dev-secret', { expiresIn: '7d' });
  res.json({ token, device: { id: device.id, displayName: device.displayName, accountNumber: device.accountNumber } });
});

// Socket.IO connection handling
io.on('connection', (socket) => {
  console.log(`🔌 New connection: ${socket.id}`);

  let currentRoom = null;
  function ensureProactiveConsumersForPeer(sid, peer) {
    try {
      if (!peer || !peer.companyId || !peer.deviceId) return;
      const memberships = DATA.memberships.filter(m => m.deviceId === peer.deviceId).map(m => m.channelId);
      memberships.forEach((channelId) => {
        const room = rooms.get(channelId);
        if (!room) return;
        const speakerSocketId = room.currentSpeaker;
        const sp = speakerSocketId ? peers.get(speakerSocketId) : null;
        if (!sp || !sp.producer) return;
        const caps = peer.rtpCaps;
        if (!caps || !router.canConsume({ producerId: sp.producer.id, rtpCapabilities: caps })) return;
        const recvT = peer.recvTransport;
        if (!recvT) return;
        if (peer.consumers && peer.consumers.has(sp.producer.id)) return;
        recvT.consume({ producerId: sp.producer.id, rtpCapabilities: caps, paused: true }).then((consumer) => {
          if (!peer.consumers) peer.consumers = new Map();
          peer.consumers.set(sp.producer.id, consumer);
          io.to(sid).emit('consumer-created', {
            id: consumer.id,
            producerId: consumer.producerId,
            kind: consumer.kind,
            rtpParameters: consumer.rtpParameters,
            type: consumer.type
          });
        }).catch((e) => {
          console.error('Proactive consume on caps/recv error:', e.message || e);
        });
      });
    } catch (e) {
      console.error('ensureProactiveConsumersForPeer error:', e.message || e);
    }
  }

  // Socket auth: attach company/device to this socket
  socket.on('auth:connect', ({ token, userName }) => {
    try {
      const payload = jwt.verify(token, process.env.JWT_SECRET || 'dev-secret');
      const { companyId, deviceId } = payload;
      const existing = peers.get(socket.id) || {};
      peers.set(socket.id, { ...existing, companyId, deviceId, userName, rtpCaps: null, recvTransport: null, sendTransport: null, consumers: new Map() });
      presence.set(deviceId, { online: true, lastSeen: Date.now() });
      socket.emit('auth:ok', { deviceId, companyId });
      socket.emit('rtp-capabilities', router.rtpCapabilities);
    } catch (e) {
      socket.emit('auth:error', { error: 'invalid token' });
    }
  });

  socket.on('client-rtp-caps', ({ rtpCapabilities }) => {
    const peer = peers.get(socket.id);
    if (peer) peer.rtpCaps = rtpCapabilities;
    const p = peers.get(socket.id);
    if (p) ensureProactiveConsumersForPeer(socket.id, p);
  });

  // Join a PTT room
  socket.on('join-room', async ({ roomId, userId, userName }) => {
    try {
      console.log(`👤 ${userName} (${userId}) joining room: ${roomId}`);
      const p = peers.get(socket.id);
      if (p && p.companyId) {
        const channel = findChannel(roomId);
        if (!channel || channel.companyId !== p.companyId || !isMember(roomId, p.deviceId)) {
          socket.emit('join-error', { error: 'not a member or invalid channel' });
          return;
        }
      }
      
      // Create room if it doesn't exist
      if (!rooms.has(roomId)) {
        rooms.set(roomId, new PTTRoom(roomId));
        console.log(`📁 Created new room: ${roomId}`);
      }

      const room = rooms.get(roomId);
      room.addPeer(socket.id, userId, userName);
      currentRoom = roomId;

      // Create WebRTC transport for this peer
      const transport = await router.createWebRtcTransport({
        listenIps: [{
          ip: '0.0.0.0',
          announcedIp: ANNOUNCED_IP
        }],
        enableUdp: true,
        enableTcp: true,
        preferUdp: true,
        initialAvailableOutgoingBitrate: 1000000
      });

      // Store peer info
      const existing = peers.get(socket.id) || {};
      peers.set(socket.id, {
        ...existing,
        roomId,
        userId,
        userName,
        transport,
        producer: null,
        consumer: null
      });

      // Send transport info to client
      socket.emit('transport-created', {
        id: transport.id,
        iceParameters: transport.iceParameters,
        iceCandidates: transport.iceCandidates,
        dtlsParameters: transport.dtlsParameters
      });

      // Also send router RTP capabilities (required by clients to decide codecs)
      socket.emit('rtp-capabilities', router.rtpCapabilities);

      // Notify others in the room
      socket.to(roomId).emit('user-joined', {
        userId,
        userName,
        totalUsers: room.peers.size
      });

      // Send current room state to joiner
      const speakerInfo = room.getSpeakerInfo();
      if (speakerInfo) {
        socket.emit('user-speaking', {
          roomId,
          userId: speakerInfo.id,
          userName: speakerInfo.name
        });

        const speakerSocketId = room.currentSpeaker;
        if (speakerSocketId) {
          const sp = peers.get(speakerSocketId);
          if (sp && sp.producer) {
            socket.emit('new-producer', {
              roomId,
              producerId: sp.producer.id,
              userId: sp.userId,
              userName: sp.userName
            });
          }
        }
      }

      // Join socket room for broadcasting
      socket.join(roomId);

      socket.emit('join-success', {
        roomId,
        userId,
        roomSize: room.peers.size
      });

      console.log(`✅ ${userName} joined room ${roomId}. Room size: ${room.peers.size}`);

    } catch (error) {
      console.error('Join room error:', error);
      socket.emit('join-error', { error: error.message });
    }
  });

  // Request to speak (PTT pressed)
  socket.on('request-speak', ({ roomId, userId }) => {
    const room = rooms.get(roomId);
    if (!room) {
      socket.emit('speak-error', { error: 'Room not found' });
      return;
    }

    const result = room.requestToSpeak(socket.id);
    
    if (result.granted) {
      // Grant permission to speaker
      socket.emit('speak-granted');
      
      // Notify all others in the roomm
      socket.to(roomId).emit('user-speaking', {
        roomId,
        userId,
        userName: peers.get(socket.id)?.userName || 'Unknown'
      });

      console.log(`🎤 ${peers.get(socket.id)?.userName} started speaking in ${roomId}`);
    } else {
      // Deny - channel busy
      socket.emit('channel-busy', {
        currentSpeaker: result.currentSpeaker,
        speakerId: result.speakerId
      });
    }
  });

  socket.on('page', ({ toDeviceId, roomId, fromUserId, fromUserName }) => {
    try {
      const targets = [];
      peers.forEach((info, sid) => {
        if (info && info.deviceId === toDeviceId) targets.push(sid);
      });
      targets.forEach((sid) => {
        io.to(sid).emit('page', { roomId, fromUserId, fromUserName });
      });
    } catch (e) {}
  });

  // Stop speaking (PTT released)
  socket.on('stop-speaking', ({ roomId, userId }) => {
    const room = rooms.get(roomId);
    if (!room) return;

    const stopped = room.stopSpeaking(socket.id);
    if (stopped) {
      // Notify all in room
      io.to(roomId).emit('user-stopped', { roomId, userId });
      console.log(`🔇 ${peers.get(socket.id)?.userName} stopped speaking in ${roomId}`);
    }
  });

  // WebRTC: Connect transport
  socket.on('connect-transport', async ({ transportId, dtlsParameters }) => {
    try {
      const peer = peers.get(socket.id);
      if (!peer) return;

      let transport = peer.transport;
      if (transport && transport.id !== transportId && peer.transports && peer.transports.has(transportId)) {
        transport = peer.transports.get(transportId);
      }
      if (!transport) return;

      await transport.connect({ dtlsParameters });
      console.log(`🔗 Transport connected for ${socket.id}`);
    } catch (error) {
      console.error('Transport connect error:', error);
    }
  });

  // WebRTC: Produce audio (start sending audio)
  socket.on('produce-audio', async ({ transportId, kind, rtpParameters }, ack) => {
    try {
      const peer = peers.get(socket.id);
      if (!peer) return;

      let transport = peer.transport;
      if (transport && transport.id !== transportId && peer.transports && peer.transports.has(transportId)) {
        transport = peer.transports.get(transportId);
      }
      if (!transport) return;

      // Only allow production if this peer is the current speaker (PTT granted)
      const room = rooms.get(peer.roomId);
      if (room && room.currentSpeaker !== socket.id) {
        socket.emit('speak-error', { error: 'Not the current speaker' });
        return;
      }

      const producer = await transport.produce({
        kind,
        rtpParameters
      });

      peer.producer = producer;
      console.log(`🎙️ Audio producer created for ${peer.userName}`);

      socket.emit('producer-ok', {
        producerId: producer.id
      });

      if (typeof ack === 'function') {
        try {
          ack({ producerId: producer.id });
        } catch (e) {}
      }

      // Notify others in the room about the new producer
      socket.to(peer.roomId).emit('new-producer', {
        roomId: peer.roomId,
        producerId: producer.id,
        userId: peer.userId,
        userName: peer.userName
      });

      const roomId = peer.roomId;
      const members = DATA.memberships.filter(m => m.channelId === roomId).map(m => m.deviceId);
      peers.forEach(async (p2, sid2) => {
        try {
          if (!p2 || sid2 === socket.id) return;
          if (!p2.companyId || !p2.deviceId) return;
          if (!members.includes(p2.deviceId)) return;
          const caps = p2.rtpCaps;
          if (!caps || !router.canConsume({ producerId: producer.id, rtpCapabilities: caps })) return;
          const recvT = p2.recvTransport;
          if (!recvT) return;
          if (p2.consumers && p2.consumers.has(producer.id)) return;
          const consumer = await recvT.consume({ producerId: producer.id, rtpCapabilities: caps, paused: true });
          if (!p2.consumers) p2.consumers = new Map();
          p2.consumers.set(producer.id, consumer);
          io.to(sid2).emit('consumer-created', {
            id: consumer.id,
            producerId: consumer.producerId,
            kind: consumer.kind,
            rtpParameters: consumer.rtpParameters,
            type: consumer.type
          });
        } catch (e) {
          console.error('Proactive consume error:', e.message || e);
        }
      });

      // When producer is closed (user leaves)
      producer.on('transportclose', () => {
        console.log(`❌ Producer transport closed for ${peer.userName}`);
      });

    } catch (error) {
      console.error('Produce error:', error);
    }
  });

  // WebRTC: Consume audio (start receiving audio)
  socket.on('consume-audio', async ({ producerId, rtpCapabilities, transportId }) => {
    try {
      const peer = peers.get(socket.id);
      if (!peer || !router.canConsume({ producerId, rtpCapabilities })) {
        return;
      }

      let transport = peer.transport;
      if (transportId && peer.transports && peer.transports.has(transportId)) {
        transport = peer.transports.get(transportId);
      }
      if (!transport) return;

      const consumer = await transport.consume({
        producerId,
        rtpCapabilities,
        paused: true // Start paused, resume when someone speaks
      });

      peer.consumer = consumer;
      
      socket.emit('consumer-created', {
        id: consumer.id,
        producerId: consumer.producerId,
        kind: consumer.kind,
        rtpParameters: consumer.rtpParameters,
        type: consumer.type
      });

      console.log(`🔊 Audio consumer created for ${peer.userName}`);

    } catch (error) {
      console.error('Consume error:', error);
    }
  });

  // Create additional WebRTC transport (send/recv)
  socket.on('create-transport', async ({ direction }) => {
    try {
      const peer = peers.get(socket.id);
      if (!peer) return;

      const transport = await router.createWebRtcTransport({
        listenIps: [{
          ip: '0.0.0.0',
          announcedIp: ANNOUNCED_IP
        }],
        enableUdp: true,
        enableTcp: true,
        preferUdp: true,
        initialAvailableOutgoingBitrate: 1000000
      });

      if (!peer.transports) peer.transports = new Map();
      peer.transports.set(transport.id, transport);
      if (direction === 'recv') peer.recvTransport = transport;
      else if (direction === 'send') peer.sendTransport = transport;

      socket.emit('transport-created', {
        direction,
        id: transport.id,
        iceParameters: transport.iceParameters,
        iceCandidates: transport.iceCandidates,
        dtlsParameters: transport.dtlsParameters
      });

      if (direction === 'recv') ensureProactiveConsumersForPeer(socket.id, peer);
    } catch (error) {
      console.error('Create transport error:', error);
    }
  });

  // WebRTC: Resume consumer (start playing audio)
  socket.on('resume-consumer', async () => {
    try {
      const peer = peers.get(socket.id);
      if (peer && peer.consumers && peer.consumers.size > 0) {
        for (const c of peer.consumers.values()) {
          try { await c.resume(); } catch (e) { console.error('Resume error:', e.message || e); }
        }
        console.log(`▶️ Consumer resumed for ${peer.userName}`);
      }
    } catch (error) {
      console.error('Resume error:', error);
    }
  });

  // WebRTC: Pause consumer (stop playing audio)
  socket.on('pause-consumer', async () => {
    try {
      const peer = peers.get(socket.id);
      if (peer && peer.consumers && peer.consumers.size > 0) {
        for (const c of peer.consumers.values()) {
          try { await c.pause(); } catch (e) { console.error('Pause error:', e.message || e); }
        }
        console.log(`⏸️ Consumer paused for ${peer.userName}`);
      }
    } catch (error) {
      console.error('Pause error:', error);
    }
  });

  // Leave room
  socket.on('leave-room', ({ roomId }) => {
    const room = rooms.get(roomId);
    if (room) {
      room.removePeer(socket.id);
      
      // Notify others
      socket.to(roomId).emit('user-left', {
        userId: peers.get(socket.id)?.userId
      });

      // Clean up if room is empty
      if (room.peers.size === 0) {
        rooms.delete(roomId);
        console.log(`🗑️ Room ${roomId} deleted (empty)`);
      }
    }

    // Clean up peer
    const peer = peers.get(socket.id);
    if (peer) {
      if (peer.producer) peer.producer.close();
      if (peer.consumer) peer.consumer.close();
      if (peer.transport) peer.transport.close();
    }
    peers.delete(socket.id);

    socket.leave(roomId);
    console.log(`👋 ${peer?.userName || socket.id} left room ${roomId}`);
  });

  // Handle disconnect
  socket.on('disconnect', () => {
    if (currentRoom) {
      const room = rooms.get(currentRoom);
      if (room) {
        room.removePeer(socket.id);
        
        // Notify others
        socket.to(currentRoom).emit('user-left', {
          userId: peers.get(socket.id)?.userId
        });

        // Clean up if room is empty
        if (room.peers.size === 0) {
          rooms.delete(currentRoom);
          console.log(`🗑️ Room ${currentRoom} deleted (empty)`);
        }
      }
    }

    // Clean up peer
    const peer = peers.get(socket.id);
    if (peer) {
      if (peer.producer) peer.producer.close();
      if (peer.consumer) peer.consumer.close();
      if (peer.transport) peer.transport.close();
    }
    peers.delete(socket.id);

    console.log(`❌ Disconnected: ${socket.id} (${peer?.userName || 'Unknown'})`);
  });
});

// Health check endpoint
app.get('/health', (req, res) => {
  res.json({
    status: 'ok',
    rooms: rooms.size,
    peers: peers.size,
    mediasoup: worker ? 'running' : 'stopped'
  });
});

// Presence endpoint per company
app.get('/v1/companies/:companyId/presence', (req, res) => {
  const { companyId } = req.params;
  const out = {};
  DATA.devices.filter(d => d.companyId === companyId).forEach(d => {
    const p = presence.get(d.id) || { online: false, lastSeen: null };
    out[d.id] = { online: !!p.online, lastSeen: p.lastSeen };
  });
  res.json(out);
});

// Start server
const PORT = process.env.PORT || 3001;
server.listen(PORT, '0.0.0.0', () => {
  console.log(`🚀 PTT Server running on port ${PORT}`);
  console.log(`📡 Health check: http://localhost:${PORT}/health`);
  console.log(`🔗 WebSocket: ws://localhost:${PORT}`);
});
