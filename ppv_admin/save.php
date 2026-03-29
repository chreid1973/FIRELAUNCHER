<?php
declare(strict_types=1);

header('Content-Type: application/json; charset=utf-8');

const ADMIN_SECRET = 'doireallyneedsixteenletters';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    respond(405, [
        'ok' => false,
        'message' => 'Only POST requests are allowed.'
    ]);
}

if (ADMIN_SECRET === 'CHANGE_ME_TO_A_LONG_RANDOM_SECRET' || strlen(ADMIN_SECRET) < 16) {
    respond(500, [
        'ok' => false,
        'message' => 'Set a real ADMIN_SECRET in save.php before using direct publish.'
    ]);
}

$rawBody = file_get_contents('php://input');
$request = json_decode($rawBody ?: '', true);

if (!is_array($request)) {
    respond(400, [
        'ok' => false,
        'message' => 'Request body must be valid JSON.'
    ]);
}

$secret = trim((string)($request['secret'] ?? ''));
if ($secret === '' || !hash_equals(ADMIN_SECRET, $secret)) {
    respond(403, [
        'ok' => false,
        'message' => 'Publish secret is invalid.'
    ]);
}

$feed = trim((string)($request['feed'] ?? ''));
$targetMap = [
    'ppv1' => 'events_ppv_1.json',
    'ppv2' => 'events_ppv_2.json',
    'nhl' => 'events_nhl.json',
    'mlb' => 'events_mlb.json',
    'nba' => 'events_nba.json',
];

if (!array_key_exists($feed, $targetMap)) {
    respond(400, [
        'ok' => false,
        'message' => 'Feed must be one of: ppv1, ppv2, nhl, mlb, nba.'
    ]);
}

$payload = $request['payload'] ?? null;
if (!is_array($payload)) {
    respond(400, [
        'ok' => false,
        'message' => 'Payload object is required.'
    ]);
}

try {
    $validatedPayload = validatePayload($payload);
    $encodedJson = json_encode($validatedPayload, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES);
    if (!is_string($encodedJson)) {
        throw new RuntimeException('JSON encoding failed.');
    }

    $targetFile = __DIR__ . DIRECTORY_SEPARATOR . $targetMap[$feed];
    $backupRelativePath = createBackupIfNeeded($targetFile);

    $bytesWritten = @file_put_contents($targetFile, $encodedJson . PHP_EOL, LOCK_EX);
    if ($bytesWritten === false) {
        throw new RuntimeException('Unable to write the target JSON file.');
    }

    respond(200, [
        'ok' => true,
        'message' => 'Saved ' . $targetMap[$feed] . ' successfully.',
        'filename' => $targetMap[$feed],
        'backup' => $backupRelativePath
    ]);
} catch (InvalidArgumentException $error) {
    respond(400, [
        'ok' => false,
        'message' => $error->getMessage()
    ]);
} catch (Throwable $error) {
    respond(500, [
        'ok' => false,
        'message' => $error->getMessage()
    ]);
}

function validatePayload(array $payload): array
{
    $date = trim((string)($payload['date'] ?? ''));
    if (!preg_match('/^\d{4}-\d{2}-\d{2}$/', $date)) {
        throw new InvalidArgumentException('Payload date must use YYYY-MM-DD format.');
    }

    $timezone = trim((string)($payload['timezone'] ?? ''));
    if ($timezone === '') {
        throw new InvalidArgumentException('Payload timezone is required.');
    }

    try {
        new DateTimeZone($timezone);
    } catch (Throwable $error) {
        throw new InvalidArgumentException('Payload timezone is not a valid PHP timezone identifier.');
    }

    $events = $payload['events'] ?? null;
    if (!is_array($events)) {
        throw new InvalidArgumentException('Payload events must be an array.');
    }

    $normalizedEvents = [];
    foreach ($events as $index => $event) {
        if (!is_array($event)) {
            throw new InvalidArgumentException('Event #' . ($index + 1) . ' must be an object.');
        }

        $channel = trim((string)($event['channel'] ?? ''));
        $time = trim((string)($event['time'] ?? ''));
        $title = trim((string)($event['title'] ?? ''));

        if ($channel === '') {
            throw new InvalidArgumentException('Event #' . ($index + 1) . ' is missing a channel.');
        }
        if (!isValidTime($time)) {
            throw new InvalidArgumentException('Event #' . ($index + 1) . ' has an invalid time.');
        }
        if ($title === '') {
            throw new InvalidArgumentException('Event #' . ($index + 1) . ' is missing a title.');
        }

        $normalizedEvents[] = [
            'channel' => $channel,
            'time' => $time,
            'title' => $title,
        ];
    }

    return [
        'date' => $date,
        'timezone' => $timezone,
        'events' => $normalizedEvents,
    ];
}

function isValidTime(string $value): bool
{
    if (!preg_match('/^(\d{2}):(\d{2})$/', $value, $matches)) {
        return false;
    }

    $hours = (int)$matches[1];
    $minutes = (int)$matches[2];
    return $hours >= 0 && $hours <= 23 && $minutes >= 0 && $minutes <= 59;
}

function createBackupIfNeeded(string $targetFile): ?string
{
    if (!is_file($targetFile)) {
        return null;
    }

    $backupDirectory = __DIR__ . DIRECTORY_SEPARATOR . 'backups';
    if (!is_dir($backupDirectory) && !mkdir($backupDirectory, 0755, true) && !is_dir($backupDirectory)) {
        throw new RuntimeException('Unable to create the backups directory.');
    }

    $baseName = pathinfo($targetFile, PATHINFO_FILENAME);
    $backupName = $baseName . '_' . date('Ymd_His') . '.json';
    $backupPath = $backupDirectory . DIRECTORY_SEPARATOR . $backupName;

    if (!@copy($targetFile, $backupPath)) {
        throw new RuntimeException('Unable to create a backup before overwrite.');
    }

    return 'backups/' . $backupName;
}

function respond(int $statusCode, array $payload): void
{
    http_response_code($statusCode);
    echo json_encode($payload, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES);
    exit;
}
