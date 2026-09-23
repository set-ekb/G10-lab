"""Regression tests for passive evidence accounting, not device control."""
import csv
import tempfile
import unittest
from pathlib import Path
from analyze_lab import analyze


BASE = bytes.fromhex('24 22 01 25 F2 12 00 00 00 00 00 00 00 00 E5 08 19 00 40 00')


def row(source='FFF2_NOTIFY', packet=BASE, marker='', time='2026-09-17 10:00:00.000', **kw):
    result = dict(time=time, source=source, uuid='fff2', length=str(len(packet)),
                  marker=marker, hex=packet.hex(' '), note='')
    result.update(kw)
    return result


class LabTest(unittest.TestCase):
    def report(self, rows, old=False):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'lab.csv'
            fields = ['time', 'source', 'uuid', 'length', 'marker', 'hex', 'ascii_or_note' if old else 'note']
            with path.open('w', encoding='utf-8-sig', newline='') as file:
                writer = csv.DictWriter(file, fieldnames=fields, extrasaction='ignore')
                writer.writeheader()
                writer.writerows(rows)
            return analyze(path)

    def test_real_sample(self):
        report = analyze(Path(__file__).resolve().parent.parent / 'lab-samples/g10_ble_20260812_174109.csv')
        self.assertEqual((report['valid_notifications'], report['tx_count'], report['invalid_rows']), (49, 1, 0))
        self.assertEqual(report['tx_examples'][0]['hex'], 'F0 4D 13')
        self.assertIn('ACK unknown', report['tx_examples'][0]['status'])
        self.assertEqual(report['streams'][0]['marker_difference_indexes'], [])

    def test_old_duplicates_are_not_notifications_or_markers(self):
        report = self.report([row(), row('PROTOCOL_DETECT'), row('FFF2_READ'),
                              row('FFF1_TX', b'\xf0\x4c\x03\x01', marker='ECO')], old=True)
        self.assertEqual(report['valid_notifications'], 1)
        self.assertEqual(report['streams'][0]['by_marker'][0]['marker'], '')
        self.assertEqual(report['streams'][0]['timing']['rate_hz'], None)

    def test_marker_bits_and_equal_notifications_are_preserved(self):
        brake = bytearray(BASE); brake[18] = 0x48
        report = self.report([row(marker='off'), row(marker='off', time='2026-09-17 10:00:00.500'),
                              row(packet=brake, marker='on, brake', time='2026-09-17 10:00:01.000')])
        stream = report['streams'][0]
        self.assertEqual(stream['count'], 3)
        self.assertEqual(stream['marker_difference_indexes'], [18])
        self.assertEqual(stream['changing_bytes'][0]['varying_bits'], '08')
        self.assertEqual(stream['timing']['rate_hz'], 2)

    def test_bad_hex_and_length_never_count(self):
        report = self.report([row(hex='24 GG'), row(length='19'), row(hex='2 422'), row()])
        self.assertEqual(report['invalid_rows'], 3)
        self.assertEqual(report['valid_notifications'], 1)

    def test_unknown_frames_are_kept_separate(self):
        report = self.report([row(), row(packet=b'\x55\x07\x00\x01\x00\x00\x00'), row(uuid='other')])
        self.assertEqual(len(report['streams']), 3)
        self.assertEqual(report['valid_notifications'], 3)

    def test_clock_rollback_does_not_invent_rate(self):
        stream = self.report([row(time='2026-09-17 10:00:01'), row()])['streams'][0]
        self.assertFalse(stream['timing']['valid'])
        self.assertNotIn('rate_hz', stream['timing'])

    def test_bad_time_keeps_evidence(self):
        report = self.report([row(time='bad timestamp')])
        self.assertEqual(report['valid_notifications'], 1)
        self.assertFalse(report['streams'][0]['timing']['valid'])


if __name__ == '__main__':
    unittest.main()
