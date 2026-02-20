import csv
import sys
import re
import base64
from email.mime.text import MIMEText
from pathlib import Path

from google.auth.transport.requests import Request
from google.oauth2.credentials import Credentials
from google_auth_oauthlib.flow import InstalledAppFlow
from googleapiclient.discovery import build

BASE_DIR = Path(__file__).parent
SCOPES = ['https://www.googleapis.com/auth/gmail.compose']
TOKEN_FILE = BASE_DIR / 'token.json'
CREDS_FILE = BASE_DIR / 'credentials.json'

def get_gmail_service():
    creds = None
    if TOKEN_FILE.exists():
        creds = Credentials.from_authorized_user_file(str(TOKEN_FILE), SCOPES)
    if not creds or not creds.valid:
        if creds and creds.expired and creds.refresh_token:
            creds.refresh(Request())
        else:
            flow = InstalledAppFlow.from_client_secrets_file(str(CREDS_FILE), SCOPES)
            creds = flow.run_local_server(port=0)
        TOKEN_FILE.write_text(creds.to_json())
    return build('gmail', 'v1', credentials=creds)

def create_draft(service, to_email, subject, body_text):
    html_body = body_text.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
    html_body = html_body.replace('\n', '<br>\n')
    # Restore links that were escaped
    html_body = re.sub(r'&lt;(https?://\S+?)&gt;', r'<a href="\1">\1</a>', html_body)
    html_body = re.sub(r'(https?://\S+?)(?=<br>|$|\s)', r'<a href="\1">\1</a>', html_body)
    message = MIMEText(html_body, 'html')
    message['to'] = to_email
    message['subject'] = subject
    raw = base64.urlsafe_b64encode(message.as_bytes()).decode()
    draft = service.users().drafts().create(
        userId='me', body={'message': {'raw': raw}}
    ).execute()
    return draft

def load_names(path):
    names = {}
    with open(path, newline='') as f:
        reader = csv.DictReader(f)
        for row in reader:
            num = row['Number'].strip()
            full_name = row['Name'].strip()
            names[num] = {
                'name': full_name,
                'first_name': full_name.split()[0],
                'email': row['email'].strip(),
            }
    return names

def load_trios(path):
    with open(path, newline='') as f:
        reader = csv.reader(f)
        headers = [h.strip() for h in next(reader)]
        rows = [[c.strip() for c in row] for row in reader if any(c.strip() for c in row)]
    return headers, rows

def load_links(path):
    links = {}
    with open(path) as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            m = re.match(r'\[(mp3|wav)\]\s+(\S+)\.\w+:\s+(https?://\S+)', line)
            if m:
                fmt, name, url = m.groups()
                if name not in links:
                    links[name] = {}
                links[name][fmt] = url
    return links

def load_template(path):
    with open(path) as f:
        return f.read()

def generate_emails(round_id, deadline):
    names = load_names(BASE_DIR / 'names.txt')
    headers, rows = load_trios(BASE_DIR / 'trios.txt')
    links = load_links(BASE_DIR / round_id / 'links.txt')
    template = load_template(BASE_DIR / 'email_template.txt')

    try:
        col = headers.index(round_id)
    except ValueError:
        print(f"Round '{round_id}' not found in trios.txt headers: {headers}")
        sys.exit(1)

    next_col = col + 1
    if next_col >= len(headers):
        print(f"No next round after '{round_id}' in trios.txt")
        sys.exit(1)

    next_round = headers[next_col]

    emails = []
    for row in rows:
        source_num = row[col]
        recipient_num = row[next_col]

        person = names[recipient_num]
        file_key = f"{round_id}{source_num}"

        mp3_url = links[file_key]['mp3']
        wav_url = links[file_key]['wav']

        body = template.replace('<FIRST_NAME>', person['first_name'])
        body = body.replace('<NAME>', person['name'])
        body = body.replace('<ROUND>', next_round)
        body = body.replace('<DEADLINE>', deadline)
        body = body.replace('<MP3>', mp3_url)
        body = body.replace('<WAV>', wav_url)

        emails.append({
            'to': person['email'],
            'to_name': person['name'],
            'subject': f'Wildcard Session 11 - Round {next_round}',
            'body': body,
            'track': file_key,
        })

    return emails

if __name__ == '__main__':
    if len(sys.argv) < 3:
        print(f"Usage: {sys.argv[0]} <round_id> <deadline> [--draft-first | --draft-all]")
        print(f'Example: {sys.argv[0]} DD "10 March 2026" --draft-first')
        sys.exit(1)

    round_id = sys.argv[1]
    deadline = sys.argv[2]
    mode = sys.argv[3] if len(sys.argv) > 3 else '--preview'

    emails = generate_emails(round_id, deadline)

    if mode == '--preview':
        first = emails[0]
        print(f"=== FIRST EMAIL (preview) ===")
        print(f"To: {first['to_name']} <{first['to']}>")
        print(f"Subject: {first['subject']}")
        print(f"Track: {first['track']}")
        print(f"---")
        print(first['body'])

    elif mode == '--draft-first':
        service = get_gmail_service()
        first = emails[0]
        draft = create_draft(service, first['to'], first['subject'], first['body'])
        print(f"Draft created for: {first['to_name']} <{first['to']}>")
        print(f"Track: {first['track']}")
        print(f"Draft ID: {draft['id']}")
        print("Check your Gmail Drafts folder.")

    elif mode == '--draft-all':
        service = get_gmail_service()
        for i, email in enumerate(emails, 1):
            draft = create_draft(service, email['to'], email['subject'], email['body'])
            print(f"[{i}/{len(emails)}] Draft created for: {email['to_name']} <{email['to']}> (track {email['track']})")
        print(f"\nAll {len(emails)} drafts created. Check your Gmail Drafts folder.")

    else:
        print(f"Unknown mode: {mode}")
        print("Use --preview, --draft-first, or --draft-all")
        sys.exit(1)
