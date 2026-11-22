import { DefaultArtifactClient } from "@actions/artifact";
import { existsSync, statSync } from "node:fs";
import { dirname, resolve, sep } from "node:path";

const workspace = process.env.GITHUB_WORKSPACE;
const inputPath = process.env.INPUT_APK_PATH;
if (!workspace || !inputPath) {
  throw new Error("GITHUB_WORKSPACE and apk-path input are required");
}
if (!process.env.ACTIONS_RUNTIME_TOKEN || !process.env.ACTIONS_RESULTS_URL) {
  throw new Error("GitHub Actions artifact runtime credentials are unavailable");
}

const root = resolve(workspace);
const apkPath = resolve(root, inputPath);
if (apkPath !== root && !apkPath.startsWith(root + sep)) {
  throw new Error("APK path must remain inside the workflow workspace");
}
if (!existsSync(apkPath) || !statSync(apkPath).isFile()) {
  throw new Error(`APK does not exist: ${apkPath}`);
}

const client = new DefaultArtifactClient();
const uploaded = await client.uploadArtifact(
  "nightmare-fork-test-apk",
  [apkPath],
  dirname(apkPath),
  { retentionDays: 1, compressionLevel: 0 },
);
console.log(`Uploaded artifact ${uploaded.id}; ${uploaded.size} bytes; one-day retention`);
