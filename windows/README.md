# Bambu Widget for Windows

Electron app. `install.bat` copies this folder to `%LOCALAPPDATA%\BambuWidget`, installs the dependencies and creates
shortcuts that start the widget through `launch.vbs` (detached from any console window).

Development: `npm install` then `npx electron .`

| File | Purpose |
|---|---|
| `main.js` | Window, tray, notifications, settings |
| `src/cloud.js` | Bambu Lab account sign-in and printer list |
| `src/hub.js` | One MQTT connection to Bambu Cloud for all printers, commands |
| `src/state.js` | Turns printer reports into what the widget shows |
| `renderer/` | Widget UI |
| `assets/errors.json.gz` | HMS / print error texts and wiki links (from ha-bambulab, MIT) |
