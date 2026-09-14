"""Run against the exact bundled yt-dlp: python app/src/test/python/test_download_sources.py."""
import importlib.util
import pathlib
import sys
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True

ROOT = pathlib.Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT / 'app/src/main/res/raw/ytdlp'))
from yt_dlp import YoutubeDL


def plugin(name):
    spec = importlib.util.spec_from_file_location('shu_' + name, ROOT / f'app/src/main/assets/ytdlp/{name}.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


dy = plugin('douyin')
bb = plugin('bilibili')


class SourceTests(unittest.TestCase):
    def test_douyin_playback_share_and_unknown(self):
        formats = [{'url': x} for x in ('play', 'download', 'other', 'https://host/playwm/1')]
        dy.DouyinIE._mark_sources(formats, {
            'play_addr': {'url_list': ['play']},
            'download_addr': {'url_list': ['download']}, 'has_watermark': True})
        self.assertEqual('absent', formats[0]['shu_watermark'])
        self.assertEqual('present', formats[1]['shu_watermark'])
        self.assertNotIn('shu_watermark', formats[2])
        self.assertEqual('present', formats[3]['shu_watermark'])

    def test_bili_missing_or_ambiguous_marker_is_not_clean(self):
        for marks in (None, [], ['0'], [None], [True], [1]):
            self.assertEqual(set(), bb.BiliBiliIE._clean_qualities({'accept_quality': [80], 'accept_watermark': marks}))
        self.assertEqual({64, 80}, bb.BiliBiliIE._clean_qualities({'accept_quality': [64, 80, 120], 'accept_watermark': [False, 0, 1]}))

    def extract(self, response, valid=True):
        ie = bb.BiliBiliIE(YoutubeDL({'quiet': True}))
        def web(extractor, url):
            extractor._shu_content_ids = (1, 2)
            return {'id': 'BVtest', 'duration': 60, 'formats': [{'format_id': 'web', 'url': 'https://web/video'}]}
        with patch.object(bb._BiliBiliIE, '_real_extract', web), \
                patch.object(ie, '_download_json', return_value=response), \
                patch.object(ie, '_is_valid_url', return_value=valid):
            return ie._real_extract('https://www.bilibili.com/video/BVtest')['formats']

    def test_tv_failure_preserves_web(self):
        for response in (None, {'code': -400}, {'code': 0, 'data': {}}, {'code': 0, 'data': None}):
            self.assertEqual(['web'], [f['format_id'] for f in self.extract(response)])

    def test_only_confirmed_reachable_tv_quality_is_added(self):
        response = {'code': 0, 'data': {'timelength': 60000, 'accept_quality': [64, 80], 'accept_watermark': [0, 1],
            'dash': {'audio': [{'id': 30280, 'base_url': 'https://tv/audio', 'codecs': 'mp4a.40.2', 'mime_type': 'audio/mp4'}],
                     'video': [{'id': q, 'height': h, 'base_url': f'https://tv/{q}', 'codecs': 'avc1', 'mime_type': 'video/mp4'}
                               for q, h in ((64, 720), (80, 1080))]}}}
        formats = self.extract(response)
        self.assertEqual(['web', 'bili_tv_64'], [f['format_id'] for f in formats])
        self.assertEqual('absent', formats[1]['shu_watermark'])
        self.assertEqual('none', formats[1]['acodec'])
        self.assertEqual(['web'], [f['format_id'] for f in self.extract(response, False)])
        response['data']['timelength'] = 15000
        self.assertEqual(['web'], [f['format_id'] for f in self.extract(response)])

    def test_preview_or_unknown_duration_is_not_a_replacement(self):
        for web, tv in ((60, 15000), (60, None), (None, 60000), (60, '60000')):
            self.assertFalse(bb.BiliBiliIE._same_duration(web, tv))
        self.assertTrue(bb.BiliBiliIE._same_duration(60, 60001))


if __name__ == '__main__':
    unittest.main()
