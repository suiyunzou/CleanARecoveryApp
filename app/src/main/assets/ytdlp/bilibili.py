# Optional TV playback source, preserving the official web extractor.
# API references: Naptie/BiliDownload and Jared-02/BBDown (see NOTICE.txt).
from yt_dlp.extractor.bilibili import BiliBiliIE as _BiliBiliIE
from yt_dlp.utils import ExtractorError


class BiliBiliIE(_BiliBiliIE):
    def _get_chapters(self, aid, cid):
        self._shu_content_ids = (aid, cid)
        return super()._get_chapters(aid, cid)

    def _real_extract(self, url):
        self._shu_content_ids = None
        info = super()._real_extract(url)
        if not self._shu_content_ids or not info.get('formats'):
            return info
        try:
            return self._add_tv_formats(info)
        except (ExtractorError, KeyError, TypeError, ValueError, AttributeError):
            # Optional source schema/network failures must not break web playback.
            return info

    def _add_tv_formats(self, info):
        aid, cid = self._shu_content_ids
        # No web cookie is forwarded to the TV domain. A TV login is distinct.
        response = self._download_json(
            'https://api.snm0516.aisee.tv/x/tv/playurl', info['id'],
            note='Checking optional watermark-free TV source', fatal=False,
            query={'object_id': aid, 'cid': cid, 'appkey': '4409e2ce8ffd12b8',
                   'build': 106500, 'device': 'android', 'fnval': 4048, 'fnver': 0,
                   'fourk': 1, 'mid': 0, 'mobi_app': 'android_tv_yst',
                   'playurl_type': 1, 'platform': 'android', 'qn': 120})
        if not isinstance(response, dict) or response.get('code') != 0:
            return info
        data = response.get('data', response)
        if not isinstance(data, dict):
            return info
        # Do not replace a full web video with a TV preview or another cut.
        if not self._same_duration(info.get('duration'), data.get('timelength')):
            return info
        clean = self._clean_qualities(data)
        if not clean or not isinstance(data.get('dash'), dict):
            return info
        # Keep only confirmed, independently playable video representations;
        # reuse the already authorised web audio without introducing TV audio IDs.
        for fmt in self.extract_formats(data):
            if (fmt.get('vcodec') == 'none' or fmt.get('quality') not in clean
                    or not fmt.get('url') or not fmt.get('height')):
                continue
            if self._is_valid_url(fmt['url'], info['id'], item='TV video'):
                fmt['format_id'] = 'bili_tv_' + fmt['format_id']
                fmt['acodec'] = 'none'
                fmt['shu_watermark'] = 'absent'
                fmt['source_preference'] = 1
                info['formats'].append(fmt)
        return info

    @staticmethod
    def _same_duration(web_seconds, tv_milliseconds):
        return (isinstance(web_seconds, (int, float)) and web_seconds > 0
                and isinstance(tv_milliseconds, (int, float)) and tv_milliseconds > 0
                and abs(tv_milliseconds / 1000 - web_seconds) <= max(1, web_seconds * 0.01))

    @staticmethod
    def _clean_qualities(data):
        qualities, marks = data.get('accept_quality'), data.get('accept_watermark')
        if not isinstance(qualities, list) or not isinstance(marks, list) or len(qualities) != len(marks):
            return set()
        # Missing/unknown values must never become an "absent" claim.
        return {q for q, mark in zip(qualities, marks)
                if isinstance(q, int) and (mark is False or type(mark) is int and mark == 0)}
