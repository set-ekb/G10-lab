#!/usr/bin/env python3
"""Read-only G10 LAB analysis. No Bluetooth, serial, network or command sender.

Accepts old ascii_or_note and new note exports; neither is used to decode bytes.
Byte positions are zero-based. Correlation with a marker is not a protocol ACK.
"""
import argparse
import csv
import hashlib
import io
import json
import re
from collections import Counter, defaultdict
from datetime import datetime
from pathlib import Path


def parse_packet(row):
    raw = row['hex'].strip()
    if not raw or not re.fullmatch(r'(?:[0-9a-fA-F]{2}\s*)+', raw):
        raise ValueError('invalid or empty HEX')
    packet = bytes.fromhex(raw)
    if int(row['length']) != len(packet):
        raise ValueError('declared length differs from HEX')
    return packet


def timing(records):
    stamps = []
    for record in records:
        try:
            stamps.append(datetime.fromisoformat(record['time']))
        except ValueError:
            return {'valid': False, 'reason': 'invalid timestamp'}
    try:
        gaps = [(b - a).total_seconds() for a, b in zip(stamps, stamps[1:])]
    except TypeError:
        return {'valid': False, 'reason': 'mixed timezone formats'}
    if any(gap < 0 for gap in gaps):
        return {'valid': False, 'reason': 'timestamps move backwards; split sessions'}
    span = sum(gaps)
    return {
        'valid': True, 'span_s': round(span, 6),
        'rate_hz': round(len(gaps) / span, 3) if span else None,
        'max_gap_s': round(max(gaps), 6) if gaps else None,
        'gaps_over_2s': sum(gap > 2 for gap in gaps),
        'equal_timestamps': sum(gap == 0 for gap in gaps),
    }


def byte_summary(records):
    result = []
    for index in range(len(records[0]['packet'])):
        values = sorted({r['packet'][index] for r in records})
        varying = 0
        for value in values:
            varying |= value ^ values[0]
        result.append({'index': index, 'values': [f'{v:02X}' for v in values],
                       'varying_bits': f'{varying:02X}'})
    return result


def describe_stream(key, records):
    uuid, length, prefix = key
    labels = defaultdict(list)
    for row in records:
        labels[row['marker']].append(row)
    by_marker = [{'marker': marker, 'count': len(rows), 'bytes': byte_summary(rows)}
                 for marker, rows in labels.items()]
    # Report differences only, never name an unknown field or infer an ACK.
    differences = []
    if len(by_marker) > 1:
        for i in range(length):
            if len({tuple(m['bytes'][i]['values']) for m in by_marker}) > 1:
                differences.append(i)
    return {'uuid': uuid, 'length': length, 'prefix': prefix,
            'count': len(records), 'timing': timing(records),
            'changing_bytes': [b for b in byte_summary(records)
                               if b['varying_bits'] != '00'],
            'by_marker': by_marker, 'marker_difference_indexes': differences}


def analyze(path):
    path = Path(path)
    data = path.read_bytes()
    reader = csv.DictReader(io.StringIO(data.decode('utf-8-sig')), strict=True)
    required = {'time', 'source', 'uuid', 'length', 'marker', 'hex'}
    if not required.issubset(reader.fieldnames or []):
        raise ValueError('not a G10 LAB CSV: required columns missing')
    sources, lengths = Counter(), Counter()
    streams = defaultdict(list)
    rejected, commands, markers = [], [], []
    received = valid_rx = invalid = tx_count = marker_count = 0
    for row in reader:
        line = reader.line_num
        if None in row or any(v is None for v in row.values()):
            invalid += 1
            if len(rejected) < 20:
                rejected.append({'line': line, 'reason': 'wrong CSV column count'})
            continue
        source = row['source']
        sources[source] += 1
        if source == 'MARKER':
            marker_count += 1
            if len(markers) < 100:
                markers.append({'time': row['time'], 'marker': row['marker']})
        if source not in ('FFF2_NOTIFY', 'FFF1_TX'):
            continue  # PROTOCOL_DETECT and FFF2_READ are not notifications.
        if source == 'FFF2_NOTIFY':
            received += 1
        try:
            packet = parse_packet(row)
        except ValueError as exc:
            invalid += 1
            if len(rejected) < 20:
                rejected.append({'line': line, 'reason': str(exc)})
            continue
        if source == 'FFF1_TX':
            tx_count += 1
            if len(commands) < 100:
                commands.append({'time': row['time'], 'hex': packet.hex(' ').upper(),
                                 'marker': row['marker'], 'status': 'logged TX, ACK unknown'})
            continue
        valid_rx += 1
        lengths[str(len(packet))] += 1
        key = (row['uuid'].lower(), len(packet), packet[:2].hex(' ').upper())
        streams[key].append({'time': row['time'], 'marker': row['marker'], 'packet': packet})
    return {
        'file': path.name, 'sha256': hashlib.sha256(data).hexdigest(),
        'sources': dict(sources), 'notify_rows': received, 'valid_notifications': valid_rx,
        'lengths': dict(lengths), 'invalid_rows': invalid, 'rejected_examples': rejected,
        'tx_count': tx_count, 'tx_examples': commands,
        'marker_count': marker_count, 'marker_examples': markers,
        'streams': [describe_stream(key, rows) for key, rows in streams.items()],
        'limits': 'BLE notifications only; not a UART/CAN capture. Marker differences '
                  'do not prove causality, command success, board identity or firmware compatibility.',
    }


def render(report):
    lines = [f"LAB: {report['file']}", f"SHA-256: {report['sha256']}",
             f"FFF2: {report['valid_notifications']} корректных / {report['notify_rows']} строк уведомлений",
             f"Ошибочных строк: {report['invalid_rows']}; TX: {report['tx_count']}",
             'Индексы байтов с нуля. TX не означает подтверждение команды.']
    for stream in report['streams']:
        lines.append(f"Поток {stream['prefix']}, {stream['length']} байт, {stream['uuid']}: {stream['count']} пакетов")
        t = stream['timing']
        if t['valid']:
            lines.append(f"  Интервал {t['span_s']} с; частота {t['rate_hz']} Гц; максимальный разрыв {t['max_gap_s']} с")
        else:
            lines.append(f"  Время непригодно для оценки частоты: {t['reason']}")
        lines.append('  Менялись байты: ' + str([b['index'] for b in stream['changing_bytes']]))
        lines.append('  Отличия между метками: ' + str(stream['marker_difference_indexes']))
        for m in stream['by_marker']:
            lines.append(f"  Метка {m['marker'] or '(без метки)'}: {m['count']} пакетов")
    lines.append('Совпадения по меткам — кандидаты для проверки, не расшифрованные команды.')
    return '\n'.join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('csv_file', type=Path)
    parser.add_argument('--json', action='store_true', help='full per-byte report as JSON')
    args = parser.parse_args()
    try:
        result = analyze(args.csv_file)
    except (OSError, UnicodeError, ValueError, csv.Error) as exc:
        parser.exit(2, f'LAB error: {exc}\n')
    print(json.dumps(result, ensure_ascii=False, indent=2) if args.json else render(result))
    return 1 if result['invalid_rows'] or not result['valid_notifications'] else 0


if __name__ == '__main__':
    raise SystemExit(main())
