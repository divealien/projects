import os
import sys
from dotenv import load_dotenv
import dropbox

load_dotenv(os.path.join(os.path.expanduser("~"), ".env"))

APP_KEY = os.getenv("DROPBOX_APP_KEY")
APP_SECRET = os.getenv("DROPBOX_APP_SECRET")
REFRESH_TOKEN = os.getenv("DROPBOX_REFRESH_TOKEN")

if not all([APP_KEY, APP_SECRET, REFRESH_TOKEN]):
    print("ERROR: Missing credentials in .env")
    print("Run dropbox_setup.py first to authorize.")
    raise SystemExit(1)

if len(sys.argv) < 3:
    print("Usage: python dropbox_links.py <dropbox_root> <folder>")
    print(r"Example: python dropbox_links.py C:\Users\davep\Dropbox share\WildCardSessions_out")
    raise SystemExit(1)

dropbox_root = sys.argv[1]
folder = sys.argv[2].replace("\\", "/")

dbx = dropbox.Dropbox(
    oauth2_refresh_token=REFRESH_TOKEN,
    app_key=APP_KEY,
    app_secret=APP_SECRET,
)


def get_shared_link(dropbox_path):
    """Retrieve an existing shared link or create a new one."""
    try:
        links = dbx.sharing_list_shared_links(path=dropbox_path).links
        if links:
            return links[0].url
        result = dbx.sharing_create_shared_link_with_settings(dropbox_path)
        return result.url
    except dropbox.exceptions.ApiError as e:
        return f"Error: {e}"


local_folder = os.path.join(dropbox_root, folder)

if not os.path.isdir(local_folder):
    print(f"ERROR: Directory not found: {local_folder}")
    raise SystemExit(1)

for dirpath, dirnames, filenames in os.walk(local_folder):
    dirnames.sort()
    for filename in sorted(filenames):
        rel_path = os.path.relpath(
            os.path.join(dirpath, filename), dropbox_root
        ).replace("\\", "/")
        dropbox_path = f"/{rel_path}"
        subdir = os.path.relpath(dirpath, local_folder).replace("\\", "/")
        if subdir == ".":
            subdir = ""
        else:
            subdir = f"[{subdir}] "
        link = get_shared_link(dropbox_path)
        print(f"{subdir}{filename}: {link}")
