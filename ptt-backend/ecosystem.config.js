module.exports = {
  apps: [{
    name: 'ptt-server',
    script: 'server.js',
    env: {
      PORT: 3001,
      JWT_SECRET: 'r65y45y456t45643y435y456t43tg6t34t336346t43gt',
      ANNOUNCED_IP: '62.84.190.56',
      RTC_MIN_PORT: 40000,
      RTC_MAX_PORT: 49999
    }
  }]
};