const bcrypt = require("bcrypt");
const jwt = require("jsonwebtoken");
const { insertUser, findUserByIdentifier, findUserById } = require("../models/userModel");


const register = async (req, res) => {
  const { username, email, password } = req.body;
  try {
    const userExists = await findUserByIdentifier(email);

    if(userExists){
        return res.status(403).json({ error: "User already exists with this email" });
    }else{
        const hashedPassword = await bcrypt.hash(password, 10);
        const user = await insertUser(username, email, hashedPassword);
        const token = jwt.sign(
        { id: user.id, username: user.username, email: user.email },
        process.env.JWT_SECRET,
        { expiresIn: "1h" }
        );
        res.json({ message: "user registered", token, user });
    }

  } catch (err) {
    res.status(400).json({ error: err.message });
  }
};

const login = async (req, res) => {
  const { identifier, password } = req.body;
  try {
    const user = await findUserByIdentifier(identifier);
    if (!user) return res.status(404).json({ error: "User not found" });

    const match = await bcrypt.compare(password, user.password);
    if (!match) return res.status(401).json({ error: "Invalid credentials" });

    const token = jwt.sign(
      { id: user.id, username: user.username, email: user.email },
      process.env.JWT_SECRET,
      { expiresIn: "1h" }
    );

    res.json({ message: "Login successful", token, user });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
};

const userExists = async (req, res) => {
  const { id } = req.params;
  try {
    const user = await findUserById(id);
    res.json(!!user);
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
};


module.exports = { register, login, userExists };
