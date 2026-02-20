# Reminder App - Requirements Spec

## Background
- Replacing BZ Reminder app (Android)
- BZ Reminder does everything right except backup — its "sync" uses proprietary servers and causes duplicate reminders
- User is proficient in Python, new to Android development
- Claude Code to write all the code; user handles build/test via Android Studio

## Tech Stack Decision
- **Kotlin + Jetpack Compose** — chosen for reliable notification support (the #1 requirement)
- **Room** for local SQLite database
- **AlarmManager** for exact alarm scheduling
- **Android Studio on Windows** for build/test
- **Dropbox SDK (Java/Kotlin)** for backup sync

## Features

### Reminders (CRUD)
- Create, edit, delete reminders
- Title and optional notes/description
- Set date and time

### Recurrence Patterns
- Daily
- Every N days (including every other day as a preset)
- Weekly on specific days (e.g. Mon, Wed, Fri)
- Monthly (specific date)
- Yearly (specific date)

### Snooze Options
- Preset quick buttons: 5min, 15min, 1hr, 1day
- Custom snooze time entry

### Notifications (Critical Requirement)
- Must survive phone sleep
- Must survive phone reboot (BOOT_COMPLETED receiver)
- Must handle battery optimization / Doze mode
- Exact alarms (AlarmManager.setExactAndAllowWhileIdle)

### Dropbox Sync
- Auto-sync in background (push latest DB on every change)
- Single user only — no conflict resolution needed
- Purely for backup and device migration
- OAuth2 with long-lived refresh token (personal app)

## Status
- Requirements gathered, ready for project structure planning and implementation
