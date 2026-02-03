module.exports = {
  apps: [
    {
      name: "ptt-backend",
      script: "./server.js",
      instances: 1,
      exec_mode: "fork",
      watch: false,
      env: {
        NODE_ENV: "production",
        PORT: 3001,
        JWT_SECRET: "rgertyrgvretrhrtyertretr",
        ANNOUNCED_IP: "62.84.190.56",
        RTC_MIN_PORT: 40000,
        RTC_MAX_PORT: 40100
      },
      autorestart: true,
      max_memory_restart: "1024M",
      restart_delay: 5000
    }
  ]
};

