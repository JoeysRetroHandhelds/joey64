// The sample report the tests post.
const INSTALL = "3f2b8c1e-9a4d-4e6f-8b2a-1c3d5e7f9a0b";
const MD5 = "9feee5da895fb2e115bb15d1c4a9f906";

export function good(events) {
  return {
    format: 1,
    install: INSTALL,
    app: "0.9.0",
    core: "dd 0ac9d17",
    device: { maker: "AYN", model: "Thor", soc: "SM8650", gpu: "Adreno 750", driver: "system 0762.10", android: 34 },
    events: events ?? [
      { type: "session", md5: MD5, header: "NSME", renderer: "parallel", scale: 1, settings: { frameskip: "auto", widescreen: true },
        seconds: 1800, emuFps: 60.1, drawnFps: 30.0, dupes: 30.1, underruns: 0, ended: "quit" },
      { type: "nocover", md5: MD5, header: "NSME", title: "Super Mario Land 64 v1.1" },
      { type: "replaced", md5: MD5, cover: "screenscraper:108717:us" },
      { type: "crash", md5: MD5, where: "core", summary: "SIGSEGV in rdp_update\n  at 0x1234" },
    ],
  };
}

