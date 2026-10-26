// Generates the Ed25519 key pair of the licence server.
//   node keygen.mjs
// SIGNING_KEY (private, PKCS#8 base64) → `wrangler secret put SIGNING_KEY`   (never commit it)
// PUBLIC_KEY  (raw 32 bytes base64)    → wrangler.toml [vars] PUBLIC_KEY  and  license.properties licensePublicKey=
import { generateKeyPairSync } from "node:crypto";

const { publicKey, privateKey } = generateKeyPairSync("ed25519");
const pkcs8 = privateKey.export({ type: "pkcs8", format: "der" }).toString("base64");
const spki = publicKey.export({ type: "spki", format: "der" });
const raw = spki.subarray(spki.length - 32).toString("base64");
console.log("SIGNING_KEY=" + pkcs8);
console.log("PUBLIC_KEY=" + raw);
