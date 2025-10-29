const request = require("supertest");
const app = require("../src/index");

describe("User Service API", () => {
  test("GET /health should return status UP", async () => {
    const res = await request(app).get("/health");
    expect(res.statusCode).toBe(200);
    expect(res.body).toEqual({ status: "UP" });
  });
});
