const Eureka = require('eureka-js-client').Eureka;
require("dotenv").config();

const eurekaHost = process.env.EUREKA_HOST || "discovery-service";
const eurekaPort = process.env.EUREKA_PORT || 8761;

const eurekaClient = new Eureka({
  instance: {
    app: 'user-service',
    instanceId: `user-service:${process.env.PORT || 8081}`,
    hostName: 'user-service', // match Docker service name
    ipAddr: 'user-service',
    statusPageUrl: `http://user-service:${process.env.PORT || 8081}/health`,
    healthCheckUrl: `http://user-service:${process.env.PORT || 8081}/health`,
    homePageUrl: `http://user-service:${process.env.PORT || 8081}`,
    port: {
      '$': process.env.PORT || 8081,
      '@enabled': 'true',
    },
    vipAddress: 'user-service',
    dataCenterInfo: {
      '@class': 'com.netflix.appinfo.InstanceInfo$DefaultDataCenterInfo',
      name: 'MyOwn',
    },
    metadata: {
      'management.port': process.env.PORT || 8081,
    }
  },
  eureka: {
    host: eurekaHost,
    port: eurekaPort,
    servicePath: '/eureka/apps/', // eureka-js-client expects this path
  },
});

module.exports = eurekaClient;
