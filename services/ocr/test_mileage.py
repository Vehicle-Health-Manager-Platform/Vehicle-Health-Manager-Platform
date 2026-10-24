import sys
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent / 'src'))
from mileage import parse_mileage


class MileageTest(unittest.TestCase):
    def parse(self, *texts, confidence=.95):
        return parse_mileage([{'text': text, 'confidence': confidence} for text in texts])

    def test_explicit_units_and_grouping(self):
        for text in ['ODO 123456 km', '123,456公里', '123，456千米', '123456 KM']:
            result = self.parse(text)
            self.assertEqual('CANDIDATE', result['status'])
            self.assertEqual(123456, result['candidates'][0]['mileage_km'])
            self.assertTrue(result['requires_confirmation'])
            self.assertFalse(result['image_recognition_performed'])

    def test_no_invented_digits_units_or_decimal_conversion(self):
        for text in ['123456', '12O456 km', '123.4 km', '12,34 km', '-123 km', '- 123 km', '−123 km', '速度80km/h', '123 miles', 'TRIP 123 km']:
            self.assertEqual([], self.parse(text)['candidates'], text)

    def test_multiple_values_not_ranked_as_fact(self):
        self.assertEqual('AMBIGUOUS', self.parse('123456 km', '123457 km')['status'])
        self.assertEqual('AMBIGUOUS', self.parse('123456 km', 'TRIP 12 km')['status'])
        self.assertEqual('LOW_CONFIDENCE', self.parse('123456 km', confidence=.89)['status'])

    def test_duplicates_zero_and_maximum(self):
        self.assertEqual(1, len(self.parse('123 km', '123公里')['candidates']))
        for value in [0, 2_000_000]:
            self.assertEqual(value, self.parse(f'{value} km')['candidates'][0]['mileage_km'])
        self.assertEqual([], self.parse('2000001 km')['candidates'])

    def test_untrusted_input_limits(self):
        for lines in [None, {}, [dict(text='1 km', confidence=True)], [dict(text='1 km', confidence=float('nan'))], [dict(text='1 km', confidence=1.1)], [dict(text='x'*501, confidence=1)], [dict(text='1\nkm', confidence=1)], [dict(text='1 km', confidence=1)]*101]:
            with self.assertRaises(ValueError):
                parse_mileage(lines)


if __name__ == '__main__':
    unittest.main()
