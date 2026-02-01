const express = require('express');
const http = require('http');
const { Server } = require('socket.io');
const cors = require('cors');
const mediasoup = require('mediasoup');

const app = express();
app.use(cors());
app.use(express.json());

const server = http.createServer(app);
const io = new Server(server, {
  cors: {
    origin: "*",
    methods: ["GET", "POST"]
  },
  // Allow older Socket.IO Android client (engine.io v3) to connect
  allowEIO3: true
});

// Store active rooms and users
const rooms = new Map(); // roomId -> { router, peers }
const peers = new Map(); // socket.id -> { roomId, transport, producer, consumer }

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
          maxaveragebitrate: 510000
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

// Socket.IO connection handling
io.on('connection', (socket) => {
  console.log(`🔌 New connection: ${socket.id}`);

  let currentRoom = null;

  // Join a PTT room
  socket.on('join-room', async ({ roomId, userId, userName }) => {
    try {
      console.log(`👤 ${userName} (${userId}) joining room: ${roomId}`);
      
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
          announcedIp: process.env.ANNOUNCED_IP || '127.0.0.1'
        }],
        enableUdp: true,
        enableTcp: true,
        preferUdp: true,
        initialAvailableOutgoingBitrate: 1000000
      });

      // Store peer info
      peers.set(socket.id, {
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
          userId: speakerInfo.id,
          userName: speakerInfo.name
        });
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
      
      // Notify all others in the room
      socket.to(roomId).emit('user-speaking', {
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

  // Stop speaking (PTT released)
  socket.on('stop-speaking', ({ roomId, userId }) => {
    const room = rooms.get(roomId);
    if (!room) return;

    const stopped = room.stopSpeaking(socket.id);
    if (stopped) {
      // Notify all in room
      io.to(roomId).emit('user-stopped', { userId });
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
  socket.on('produce-audio', async ({ transportId, kind, rtpParameters }) => {
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

      // Notify others in the room about the new producer
      socket.to(peer.roomId).emit('new-producer', {
        producerId: producer.id,
        userId: peer.userId,
        userName: peer.userName
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
          announcedIp: process.env.ANNOUNCED_IP || '127.0.0.1'
        }],
        enableUdp: true,
        enableTcp: true,
        preferUdp: true,
        initialAvailableOutgoingBitrate: 1000000
      });

      if (!peer.transports) peer.transports = new Map();
      peer.transports.set(transport.id, transport);

      socket.emit('transport-created', {
        direction,
        id: transport.id,
        iceParameters: transport.iceParameters,
        iceCandidates: transport.iceCandidates,
        dtlsParameters: transport.dtlsParameters
      });
    } catch (error) {
      console.error('Create transport error:', error);
    }
  });

  // WebRTC: Resume consumer (start playing audio)
  socket.on('resume-consumer', async () => {
    try {
      const peer = peers.get(socket.id);
      if (peer && peer.consumer) {
        await peer.consumer.resume();
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
      if (peer && peer.consumer) {
        await peer.consumer.pause();
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

// Start server
const PORT = process.env.PORT || 3001;
server.listen(PORT, '0.0.0.0', () => {
  console.log(`🚀 PTT Server running on port ${PORT}`);
  console.log(`📡 Health check: http://localhost:${PORT}/health`);
  console.log(`🔗 WebSocket: ws://localhost:${PORT}`);
});
