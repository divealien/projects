"""
One-time setup to authorize Dropbox and save a refresh token to .env

Steps before running this script:
  1. Go to https://www.dropbox.com/developers/apps
  2. Select your app (or create one with "Full Dropbox" access)
  3. Copy the App key and App secret into .env
  4. Run this script and follow the prompts
"""

import os
from dotenv import load_dotenv, set_key
from dropbox import DropboxOAuth2FlowNoRedirect

ENV_PATH = os.path.join(os.path.expanduser("~"), ".env")
load_dotenv(ENV_PATH)

app_key = os.getenv("DROPBOX_APP_KEY", "").strip()
app_secret = os.getenv("DROPBOX_APP_SECRET", "").strip()

if not app_key or not app_secret:
    print("ERROR: Set DROPBOX_APP_KEY and DROPBOX_APP_SECRET in .env first.")
    print("Get these from https://www.dropbox.com/developers/apps")
    raise SystemExit(1)

auth_flow = DropboxOAuth2FlowNoRedirect(
    app_key, app_secret, token_access_type="offline"
)

print("\n1. Go to this URL and click 'Allow':\n")
print("   " + auth_flow.start())
print("\n2. Copy the authorization code and paste it below.\n")

code = input("Authorization code: ").strip()
result = auth_flow.finish(code)

set_key(ENV_PATH, "DROPBOX_REFRESH_TOKEN", result.refresh_token)
print(f"\nRefresh token saved to {ENV_PATH}")
print("You can now run dropbox_links.py without regenerating tokens.")
