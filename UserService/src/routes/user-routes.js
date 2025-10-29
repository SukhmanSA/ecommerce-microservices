const express = require("express");
const { register, login, userExists } = require("../controllers/user-controller");

const router = express.Router();

router.post("/register", register);
router.post("/login", login);
router.post("/:id/exists", userExists);

module.exports = router;
