# Hosting the web flasher

ESP Web Tools (the button on index.html) needs the page served over HTTPS,
or opened from localhost — browsers block the USB permission prompt on a
plain http:// page. Two easy options:

## GitHub Pages (recommended — free, one-time setup)
1. Push this `web-flash/` folder's contents to a repo (or a `docs/` folder,
   or a `gh-pages` branch — whichever GitHub Pages source you prefer).
2. In the repo: Settings -> Pages -> set the source to that folder/branch.
3. GitHub gives you a URL like `https://<user>.github.io/<repo>/` — share
   that link. HTTPS is automatic.

## Quick local test (not for sharing with others)
From inside this `web-flash/` folder:
```
python3 -m http.server 8000
```
Then open `http://localhost:8000` in Chrome or Edge on the same machine.
localhost is exempt from the HTTPS requirement, but this only works on the
computer running the server — it's for testing the page before you publish
it, not for giving other people a link.
