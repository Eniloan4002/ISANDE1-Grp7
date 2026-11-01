# ICI Local 2.0

This repository contains the original static prototype and the new application workspace.

| Directory | Contents |
| --- | --- |
| [`prototype/`](prototype/index.html) | Original HTML, CSS, and JavaScript demo, including its sample data and scenario. |
| [`ICILocal2.0/frontend/`](ICILocal2.0/frontend/README.md) | React app with routing, JWT claim based route guards, responsive navigation, ICI theme variables, and screen placeholders. |
| [`ICILocal2.0/backend/`](ICILocal2.0/backend/README.md) | Reserved for the API; no backend code or framework has been chosen. |

## Run the prototype

From the repository root:

```sh
python3 -m http.server 8000 --directory prototype
```

Open `http://localhost:8000/`. The prototype uses demo role selection and does not require credentials.

## Run the React frontend

```sh
cd ICILocal2.0/frontend
npm install
npm run dev
```

Open the local address printed by Vite. The React sign-in page and screen routes are placeholders until authentication and the screens are connected. Run `npm test` for foundation tests or `npm run build` for a production build. See the [frontend README](ICILocal2.0/frontend/README.md) for the auth handoff and API configuration.
