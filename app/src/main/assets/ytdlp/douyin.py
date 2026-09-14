# Adapted 2026-09-14 from IvanaGyro/yt-dlp commit 73d89b8 (PR #2).
# Changes: standalone plugin; accurate failure error; check format URLs before offering them.
# ABogus: JohnserfSeed / f2, Apache-2.0; SM3: py-gmssl, MIT.
# Full licenses and source URLs are in the sibling ytdlp NOTICE assets.
import random
import re
import time
from yt_dlp.extractor.tiktok import DouyinIE as _BaseDouyinIE
from yt_dlp.extractor.common import InfoExtractor
from yt_dlp.utils import ExtractorError, traverse_obj


class DouyinIE(_BaseDouyinIE):
    _VALID_URL = r'https?://(?:(?:www\.)?douyin\.com/(?:video/|[^#]*[?&]modal_id=)|(?:www\.)?iesdouyin\.com/share/video/)(?P<id>[0-9]+)'
    _UPLOADER_URL_FORMAT = 'https://www.douyin.com/user/%s'
    _WEBPAGE_HOST = 'https://www.douyin.com/'

    # SM3 hash implementation ported from py-gmssl (https://pypi.org/project/gmssl/), MIT License
    _SM3_IV = [1937774191, 1226093241, 388252375, 3666478592, 2842636476, 372324522, 3817729613, 2969243214]
    _SM3_T_J = [2043430169] * 16 + [2055708042] * 48

    # ABogus signature generation ported from f2's abogus.py
    # (https://github.com/Johnserf-Seed/f2/blob/main/f2/utils/abogus.py), Apache License 2.0,
    # self-attributed there to JohnserfSeed
    _ABOGUS_CHAR = 'Dkdpgh2ZmsQB80/MfvV36XI1R45-WUAlEixNLwoqYTOPuzKFjJnry79HbGcaStCe'
    _ABOGUS_CHAR2 = 'ckdp1h4ZKsUB80/Mfvw36XIgR25+WQAlEi7NLboqYTOPuzmFjJnryx9HVGDaStCe'
    _ABOGUS_UA_KEY = b'\x00\x01\x0e'
    _ABOGUS_BIG_ARRAY = [
        121, 243, 55, 234, 103, 36, 47, 228, 30, 231, 106, 6, 115, 95, 78, 101, 250, 207, 198, 50,
        139, 227, 220, 105, 97, 143, 34, 28, 194, 215, 18, 100, 159, 160, 43, 8, 169, 217, 180, 120,
        247, 45, 90, 11, 27, 197, 46, 3, 84, 72, 5, 68, 62, 56, 221, 75, 144, 79, 73, 161,
        178, 81, 64, 187, 134, 117, 186, 118, 16, 241, 130, 71, 89, 147, 122, 129, 65, 40, 88, 150,
        110, 219, 199, 255, 181, 254, 48, 4, 195, 248, 208, 32, 116, 167, 69, 201, 17, 124, 125, 104,
        96, 83, 80, 127, 236, 108, 154, 126, 204, 15, 20, 135, 112, 158, 13, 1, 188, 164, 210, 237,
        222, 98, 212, 77, 253, 42, 170, 202, 26, 22, 29, 182, 251, 10, 173, 152, 58, 138, 54, 141,
        185, 33, 157, 31, 252, 132, 233, 235, 102, 196, 191, 223, 240, 148, 39, 123, 92, 82, 128, 109,
        57, 24, 38, 113, 209, 245, 2, 119, 153, 229, 189, 214, 230, 174, 232, 63, 52, 205, 86, 140,
        66, 175, 111, 171, 246, 133, 238, 193, 99, 60, 74, 91, 225, 51, 76, 37, 145, 211, 166, 151,
        213, 206, 0, 200, 244, 176, 218, 44, 184, 172, 49, 216, 93, 168, 53, 21, 183, 41, 67, 85,
        224, 155, 226, 242, 87, 177, 146, 70, 190, 12, 162, 19, 137, 114, 25, 165, 163, 192, 23, 59,
        9, 94, 179, 107, 35, 7, 142, 131, 239, 203, 149, 136, 61, 249, 14, 156]
    _ABOGUS_SORT_IDX = [18, 20, 52, 26, 30, 34, 58, 38, 40, 53, 42, 21, 27, 54, 55, 31, 35, 57, 39, 41, 43, 22, 28, 32, 60, 36, 23, 29, 33, 37, 44, 45, 59, 46, 47, 48, 49, 50, 24, 25, 65, 66, 70, 71]
    _ABOGUS_SORT_IDX_2 = [18, 20, 26, 30, 34, 38, 40, 42, 21, 27, 31, 35, 39, 41, 43, 22, 28, 32, 36, 23, 29, 33, 37, 44, 45, 46, 47, 48, 49, 50, 24, 25, 52, 53, 54, 55, 57, 58, 59, 60, 65, 66, 70, 71]

    @staticmethod
    def _sm3_rotl(x, n):
        return ((x << n) & 0xffffffff) | ((x >> (32 - n)) & 0xffffffff)

    @classmethod
    def _sm3_cf(cls, v_i, b_i):
        w = []
        for i in range(16):
            weight = 0x1000000
            data = 0
            for k in range(i * 4, (i + 1) * 4):
                data = data + b_i[k] * weight
                weight = int(weight / 0x100)
            w.append(data)
        for j in range(16, 68):
            p1_input = w[j - 16] ^ w[j - 9] ^ cls._sm3_rotl(w[j - 3], 15)
            w.append((p1_input ^ cls._sm3_rotl(p1_input, 15) ^ cls._sm3_rotl(p1_input, 23)) ^ cls._sm3_rotl(w[j - 13], 7) ^ w[j - 6])
        w_1 = [w[j] ^ w[j + 4] for j in range(64)]
        a, b, c, d, e, f, g, h = v_i
        for j in range(64):
            ff = (a ^ b ^ c) if j < 16 else ((a & b) | (a & c) | (b & c))
            gg = (e ^ f ^ g) if j < 16 else ((e & f) | ((~e) & g))
            ss_1 = cls._sm3_rotl((cls._sm3_rotl(a, 12) + e + cls._sm3_rotl(cls._SM3_T_J[j], j % 32)) & 0xffffffff, 7)
            ss_2 = ss_1 ^ cls._sm3_rotl(a, 12)
            tt_1 = (ff + d + ss_2 + w_1[j]) & 0xffffffff
            tt_2 = (gg + h + ss_1 + w[j]) & 0xffffffff
            d, c, b, a = c, cls._sm3_rotl(b, 9), a, tt_1
            h, g, f, e = g, cls._sm3_rotl(f, 19), e, (tt_2 ^ cls._sm3_rotl(tt_2, 9) ^ cls._sm3_rotl(tt_2, 17)) & 0xffffffff
        return [v_i[i] ^ [a, b, c, d, e, f, g, h][i] for i in range(8)]

    @classmethod
    def _sm3_hash(cls, msg):
        msg = list(msg)
        len1 = len(msg)
        msg.append(0x80)
        reserve1 = (len1 % 64) + 1
        range_end = 56 if reserve1 <= 56 else 120
        msg.extend([0x00] * (range_end - reserve1))
        msg.extend([(len1 * 8 >> (8 * (7 - i))) & 0xff for i in range(8)])
        group_count = len(msg) // 64
        B = [msg[i * 64:(i + 1) * 64] for i in range(group_count)]
        V = [cls._SM3_IV]
        for i in range(group_count):
            V.append(cls._sm3_cf(V[i], B[i]))
        return ''.join(f'{x:08x}' for x in V[-1])

    @classmethod
    def _sm3_to_array(cls, input_data):
        if isinstance(input_data, str):
            input_bytes = input_data.encode('utf-8')
        else:
            input_bytes = bytes(input_data)
        hex_result = cls._sm3_hash(list(input_bytes))
        return [int(hex_result[i:i + 2], 16) for i in range(0, len(hex_result), 2)]

    @classmethod
    def _abogus_rc4(cls, key, plaintext):
        S = list(range(256))
        j = 0
        for i in range(256):
            j = (j + S[i] + key[i % len(key)]) % 256
            S[i], S[j] = S[j], S[i]
        i = j = 0
        result = []
        for char in plaintext:
            i = (i + 1) % 256
            j = (j + S[i]) % 256
            S[i], S[j] = S[j], S[i]
            result.append(ord(char) ^ S[(S[i] + S[j]) % 256])
        return bytes(result)

    @classmethod
    def _abogus_transform(cls, bytes_list, big_array):
        big_array = list(big_array)
        bytes_str = ''.join(chr(i) for i in bytes_list)
        result = []
        index_b = big_array[1]
        initial_value = value_e = 0
        for index, char in enumerate(bytes_str):
            if index == 0:
                initial_value = big_array[index_b]
                sum_val = index_b + initial_value
                big_array[1] = initial_value
                big_array[index_b] = index_b
            else:
                sum_val = initial_value + value_e
            sum_val %= len(big_array)
            result.append(chr(ord(char) ^ big_array[sum_val]))
            value_e = big_array[(index + 2) % len(big_array)]
            sum_val = (index_b + value_e) % len(big_array)
            initial_value = big_array[sum_val]
            big_array[sum_val] = big_array[(index + 2) % len(big_array)]
            big_array[(index + 2) % len(big_array)] = initial_value
            index_b = sum_val
        return ''.join(result)

    @classmethod
    def _abogus_b64(cls, s, alphabet):
        binary = ''.join(f'{ord(c):08b}' for c in s)
        pad_len = (6 - len(binary) % 6) % 6
        binary += '0' * pad_len
        indices = [int(binary[i:i + 6], 2) for i in range(0, len(binary), 6)]
        return ''.join(alphabet[idx] for idx in indices) + '=' * (pad_len // 2)

    @classmethod
    def _abogus_encode(cls, s, alphabet):
        result = []
        for i in range(0, len(s), 3):
            if i + 2 < len(s):
                n = (ord(s[i]) << 16) | (ord(s[i + 1]) << 8) | ord(s[i + 2])
            elif i + 1 < len(s):
                n = (ord(s[i]) << 16) | (ord(s[i + 1]) << 8)
            else:
                n = ord(s[i]) << 16
            for j, k in zip(range(18, -1, -6), (0xFC0000, 0x03F000, 0x0FC0, 0x3F), strict=True):
                if j == 6 and i + 1 >= len(s):
                    break
                if j == 0 and i + 2 >= len(s):
                    break
                result.append(alphabet[(n & k) >> j])
        result.append('=' * ((4 - len(result) % 4) % 4))
        return ''.join(result)

    @classmethod
    def _generate_abogus(cls, params, user_agent):
        inner_w = random.randint(1024, 1920)
        inner_h = random.randint(768, 1080)
        browser_fp = (f'{inner_w}|{inner_h}|{inner_w + random.randint(24, 32)}|{inner_h + random.randint(75, 90)}|'
                      f'0|{random.choice([0, 30])}|0|0|{random.randint(1024, 1920)}|{random.randint(768, 1080)}|'
                      f'{random.randint(1280, 1920)}|{random.randint(800, 1080)}|{inner_w}|{inner_h}|24|24|Win32')
        start_time = int(time.time() * 1000)
        array1 = cls._sm3_to_array(cls._sm3_to_array(params + 'cus'))
        array2 = cls._sm3_to_array(cls._sm3_to_array('cus'))
        ua_enc = cls._abogus_rc4(cls._ABOGUS_UA_KEY, user_agent)
        array3 = cls._sm3_to_array(cls._abogus_b64(''.join(chr(b) for b in ua_enc), cls._ABOGUS_CHAR2))
        end_time = int(time.time() * 1000)
        ab = {8: 3, 18: 44, 66: 0, 69: 0, 70: 0, 71: 0,
              20: (start_time >> 24) & 255, 21: (start_time >> 16) & 255, 22: (start_time >> 8) & 255, 23: start_time & 255,
              24: int(start_time / 256 / 256 / 256 / 256) >> 0, 25: int(start_time / 256 / 256 / 256 / 256 / 256) >> 0,
              26: 0, 27: 0, 28: 0, 29: 0, 30: 0, 31: 1, 32: 0, 33: 0, 34: 0, 35: 0, 36: 0, 37: 14,
              38: array1[21], 39: array1[22], 40: array2[21], 41: array2[22], 42: array3[23], 43: array3[24],
              44: (end_time >> 24) & 255, 45: (end_time >> 16) & 255, 46: (end_time >> 8) & 255, 47: end_time & 255, 48: 3,
              49: int(end_time / 256 / 256 / 256 / 256) >> 0, 50: int(end_time / 256 / 256 / 256 / 256 / 256) >> 0,
              51: 0, 52: 0, 53: 0, 54: 0, 55: 0, 56: 6383, 57: 6383 & 255, 58: (6383 >> 8) & 255, 59: 0, 60: 0,
              64: len(browser_fp), 65: len(browser_fp)}
        sorted_vals = [ab.get(i, 0) for i in cls._ABOGUS_SORT_IDX]
        ab_xor = 0
        for idx in range(len(cls._ABOGUS_SORT_IDX_2) - 1):
            if idx == 0:
                ab_xor = ab.get(cls._ABOGUS_SORT_IDX_2[idx], 0)
            ab_xor ^= ab.get(cls._ABOGUS_SORT_IDX_2[idx + 1], 0)
        sorted_vals.extend([ord(c) for c in browser_fp])
        sorted_vals.append(ab_xor)
        rand_bytes = ''
        for _ in range(3):
            rd = int(random.random() * 10000)
            rand_bytes += chr(((rd & 255) & 170) | 1) + chr(((rd & 255) & 85) | 2) + chr(((rd >> 8) & 170) | 5) + chr(((rd >> 8) & 85) | 40)
        return cls._abogus_encode(rand_bytes + cls._abogus_transform(sorted_vals, cls._ABOGUS_BIG_ARRAY), cls._ABOGUS_CHAR)

    @staticmethod
    def _generate_s_v_web_id():
        base_str = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz'
        ms = int(time.time() * 1000)
        b36 = ''
        while ms > 0:
            r = ms % 36
            b36 = (str(r) if r < 10 else chr(ord('a') + r - 10)) + b36
            ms //= 36
        o = [''] * 36
        o[8] = o[13] = o[18] = o[23] = '_'
        o[14] = '4'
        for i in range(36):
            if not o[i]:
                n = int(random.random() * len(base_str))
                o[i] = base_str[3 & n | 8 if i == 19 else n]
        return f'verify_{b36}_{"".join(o)}'

    def _ensure_cookies(self, video_id, user_agent):
        if not self._get_cookies(self._WEBPAGE_HOST).get('ttwid'):
            res = self._download_webpage_handle(
                'https://ttwid.bytedance.com/ttwid/union/register/', video_id,
                'Registering ttwid cookie', 'Unable to register ttwid cookie', fatal=False,
                data=b'{"region":"cn","aid":1768,"needFid":false,"service":"www.ixigua.com","migrate_info":{"ticket":"","source":"node"},"cbUrlProtocol":"https","union":true}',
                headers={'User-Agent': user_agent, 'Content-Type': 'application/json', 'Referer': 'https://www.douyin.com/'})
            if res:
                _, urlh = res
                cookie = urlh.headers.get('Set-Cookie', '')
                if 'ttwid=' in cookie:
                    self._set_cookie('.douyin.com', 'ttwid', cookie.split('ttwid=')[1].split(';')[0])
        if not self._get_cookies(self._WEBPAGE_HOST).get('s_v_web_id'):
            self._set_cookie('.douyin.com', 's_v_web_id', self._generate_s_v_web_id())

    def _real_extract(self, url):
        video_id = self._match_id(url)
        user_agent = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0'

        self._ensure_cookies(video_id, user_agent)

        query = {'device_platform': 'webapp', 'aid': '6383', 'channel': 'channel_pc_web', 'pc_client_type': '1',
                 'version_code': '290100', 'version_name': '29.1.0', 'cookie_enabled': 'true', 'screen_width': '1920',
                 'screen_height': '1080', 'browser_language': 'en-US', 'browser_platform': 'Win32', 'browser_name': 'Edge',
                 'browser_version': '130.0.0.0', 'browser_online': 'true', 'engine_name': 'Blink', 'engine_version': '130.0.0.0',
                 'os_name': 'Windows', 'os_version': '10', 'cpu_core_num': '12', 'device_memory': '8', 'platform': 'PC',
                 'downlink': '10', 'effective_type': '4g', 'round_trip_time': '0', 'aweme_id': video_id}
        query['a_bogus'] = self._generate_abogus('&'.join(f'{k}={v}' for k, v in query.items()), user_agent)

        detail = traverse_obj(self._download_json(
            'https://www.douyin.com/aweme/v1/web/aweme/detail/', video_id,
            'Downloading web detail JSON', 'Failed to download web detail JSON', query=query,
            headers={'User-Agent': user_agent, 'Referer': 'https://www.douyin.com/'}, fatal=False), ('aweme_detail', {dict}))
        if not detail:
            raise ExtractorError('Douyin did not return video details; retry after checking the original page. The response does not establish whether the video is private or deleted.', expected=True)

        info = self._parse_aweme_video_app(detail)
        self._mark_sources(info['formats'], detail.get('video') or {})
        # API format IDs contain changing bitrate values and CDN indices. Expose
        # stable resolution/codec IDs so a fresh extraction can honour the choice.
        groups = {}
        for fmt in info['formats']:
            key = 'douyin_{}_{}x{}'.format(
                re.sub(r'[^A-Za-z0-9_]', '_', fmt.get('vcodec') or 'unknown'),
                fmt.get('width') or 0, fmt.get('height') or 0)
            if fmt.get('shu_watermark') == 'absent':
                key += '_nw'
            groups.setdefault(key, []).append(fmt)
        selected = self.get_param('format')
        available = []
        for key, candidates in groups.items():
            if selected and selected.startswith('douyin_') and selected != key:
                continue
            candidates.sort(key=lambda f: (f.get('shu_source_rank', 0), f.get('tbr') or 0), reverse=True)
            for fmt in candidates:
                if self._is_valid_url(fmt['url'], video_id, item=key):
                    fmt['format_id'] = key
                    available.append(fmt)
                    break
        info['formats'] = available
        return info

    @staticmethod
    def _mark_sources(formats, video):
        # Playback addresses avoid the platform's save/share watermark. This is
        # not evidence that a creator has not burned a logo into the picture.
        playback = set()
        for name in ('play_addr', 'play_addr_h264', 'play_addr_bytevc1'):
            playback.update((video.get(name) or {}).get('url_list') or [])
        for rate in video.get('bit_rate') or []:
            playback.update((rate.get('play_addr') or {}).get('url_list') or [])
        downloads = set((video.get('download_addr') or {}).get('url_list') or [])
        for fmt in formats:
            url = fmt.get('url', '')
            if 'playwm' in url or (url in downloads and video.get('has_watermark') is True):
                fmt['shu_watermark'] = 'present'
                fmt['shu_source_rank'] = -1
            elif url in playback:
                fmt['shu_watermark'] = 'absent'
                fmt['shu_source_rank'] = 1
            elif url in downloads and video.get('has_watermark') is False:
                fmt['shu_watermark'] = 'absent'
                fmt['shu_source_rank'] = 1


class DouyinShortLinkIE(InfoExtractor):
    _VALID_URL = r'https?://v\.douyin\.com/(?P<id>[A-Za-z0-9_-]+)'

    def _real_extract(self, url):
        video_id = self._match_id(url)
        response = self._request_webpage(url, video_id, 'Resolving Douyin share link')
        target = response.url
        if not DouyinIE.suitable(target):
            raise ExtractorError('Douyin share link did not resolve to a video; open the original page and copy its video link.', expected=True)
        return self.url_result(target, DouyinIE.ie_key())
