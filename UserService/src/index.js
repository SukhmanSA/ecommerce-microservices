const express = require("express");
const bodyParser = require("body-parser");
const eurekaClient = require("./config/eurekaClient");
const { createUserTable } = require("./models/userModel");
const authRoutes = require("./routes/user-routes");

const app = express();
app.use(bodyParser.json());
app.use("/auth", authRoutes);

app.get("/health", (req, res) => {
  res.status(200).json({ status: "UP" });
});

if (process.env.NODE_ENV !== "test") {
  setTimeout(() => {
    eurekaClient.start((error) => {
      if (error) {
        console.error("Failed to register with Eureka:", error);
        process.exit(1);
      } else {
        console.log("User service registered with Eureka");
      }
    });
  }, 30000);

  const PORT = process.env.PORT || 8081;
  app.listen(PORT, async () => {
    await createUserTable();
    console.log(`Auth service running on port ${PORT}`);
  });
}

module.exports = app; 
