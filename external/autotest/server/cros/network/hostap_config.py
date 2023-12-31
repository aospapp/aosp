# Lint as: python2, python3
# Copyright (c) 2013 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import collections
import copy
import logging

import six

from autotest_lib.client.common_lib import error
from autotest_lib.client.common_lib.cros.network import xmlrpc_security_types
from autotest_lib.server.cros.network import packet_capturer


class HostapConfig(object):
    """Parameters for router configuration."""

    # A mapping of frequency to channel number.  This includes some
    # frequencies used outside the US.
    CHANNEL_MAP = {2412: 1,
                   2417: 2,
                   2422: 3,
                   2427: 4,
                   2432: 5,
                   2437: 6,
                   2442: 7,
                   2447: 8,
                   2452: 9,
                   2457: 10,
                   2462: 11,
                   # 12, 13 are only legitimate outside the US.
                   2467: 12,
                   2472: 13,
                   # 14 is for Japan, DSSS and CCK only.
                   2484: 14,
                   # 32 valid in Europe.
                   5160: 32,
                   # 34 valid in Europe.
                   5170: 34,
                   # 36-116 valid in the US, except 38, 42, and 46, which have
                   # mixed international support.
                   5180: 36,
                   5190: 38,
                   5200: 40,
                   5210: 42,
                   5220: 44,
                   5230: 46,
                   5240: 48,
                   5260: 52,
                   5280: 56,
                   5300: 60,
                   5320: 64,
                   5500: 100,
                   5520: 104,
                   5540: 108,
                   5560: 112,
                   5580: 116,
                   # 120, 124, 128 valid in Europe/Japan.
                   5600: 120,
                   5620: 124,
                   5640: 128,
                   # 132+ valid in US.
                   5660: 132,
                   5680: 136,
                   5700: 140,
                   5710: 142,
                   # 144 is supported by a subset of WiFi chips
                   # (e.g. bcm4354, but not ath9k).
                   5720: 144,
                   5745: 149,
                   5755: 151,
                   5765: 153,
                   5785: 157,
                   5805: 161,
                   5825: 165}

    MODE_11A = 'a'
    MODE_11B = 'b'
    MODE_11G = 'g'
    MODE_11N_MIXED = 'n-mixed'
    MODE_11N_PURE = 'n-only'
    MODE_11AC_MIXED = 'ac-mixed'
    MODE_11AC_PURE = 'ac-only'

    N_CAPABILITY_HT20 = object()
    N_CAPABILITY_HT40 = object()
    N_CAPABILITY_HT40_PLUS = object()
    N_CAPABILITY_HT40_MINUS = object()
    N_CAPABILITY_GREENFIELD = object()
    N_CAPABILITY_SGI20 = object()
    N_CAPABILITY_SGI40 = object()
    ALL_N_CAPABILITIES = [N_CAPABILITY_HT20,
                          N_CAPABILITY_HT40,
                          N_CAPABILITY_HT40_PLUS,
                          N_CAPABILITY_HT40_MINUS,
                          N_CAPABILITY_GREENFIELD,
                          N_CAPABILITY_SGI20,
                          N_CAPABILITY_SGI40]

    AC_CAPABILITY_VHT160 = object()
    AC_CAPABILITY_VHT160_80PLUS80 = object()
    AC_CAPABILITY_RXLDPC = object()
    AC_CAPABILITY_SHORT_GI_80 = object()
    AC_CAPABILITY_SHORT_GI_160 = object()
    AC_CAPABILITY_TX_STBC_2BY1 = object()
    AC_CAPABILITY_RX_STBC_1 = object()
    AC_CAPABILITY_RX_STBC_12 = object()
    AC_CAPABILITY_RX_STBC_123 = object()
    AC_CAPABILITY_RX_STBC_1234 = object()
    AC_CAPABILITY_SU_BEAMFORMER = object()
    AC_CAPABILITY_SU_BEAMFORMEE = object()
    AC_CAPABILITY_BF_ANTENNA_2 = object()
    AC_CAPABILITY_SOUNDING_DIMENSION_2 = object()
    AC_CAPABILITY_MU_BEAMFORMER = object()
    AC_CAPABILITY_MU_BEAMFORMEE = object()
    AC_CAPABILITY_VHT_TXOP_PS = object()
    AC_CAPABILITY_HTC_VHT = object()
    AC_CAPABILITY_MAX_A_MPDU_LEN_EXP0 = object()
    AC_CAPABILITY_MAX_A_MPDU_LEN_EXP1 = object()
    AC_CAPABILITY_MAX_A_MPDU_LEN_EXP2 = object()
    AC_CAPABILITY_MAX_A_MPDU_LEN_EXP3 = object()
    AC_CAPABILITY_MAX_A_MPDU_LEN_EXP4 = object()
    AC_CAPABILITY_MAX_A_MPDU_LEN_EXP5 = object()
    AC_CAPABILITY_MAX_A_MPDU_LEN_EXP6 = object()
    AC_CAPABILITY_MAX_A_MPDU_LEN_EXP7 = object()
    AC_CAPABILITY_VHT_LINK_ADAPT2 = object()
    AC_CAPABILITY_VHT_LINK_ADAPT3 = object()
    AC_CAPABILITY_RX_ANTENNA_PATTERN = object()
    AC_CAPABILITY_TX_ANTENNA_PATTERN = object()
    AC_CAPABILITIES_MAPPING = {
            AC_CAPABILITY_VHT160: '[VHT160]',
            AC_CAPABILITY_VHT160_80PLUS80: '[VHT160-80PLUS80]',
            AC_CAPABILITY_RXLDPC: '[RXLDPC]',
            AC_CAPABILITY_SHORT_GI_80: '[SHORT-GI-80]',
            AC_CAPABILITY_SHORT_GI_160: '[SHORT-GI-160]',
            AC_CAPABILITY_TX_STBC_2BY1: '[TX-STBC-2BY1]',
            AC_CAPABILITY_RX_STBC_1: '[RX-STBC-1]',
            AC_CAPABILITY_RX_STBC_12: '[RX-STBC-12]',
            AC_CAPABILITY_RX_STBC_123: '[RX-STBC-123]',
            AC_CAPABILITY_RX_STBC_1234: '[RX-STBC-1234]',
            AC_CAPABILITY_SU_BEAMFORMER: '[SU-BEAMFORMER]',
            AC_CAPABILITY_SU_BEAMFORMEE: '[SU-BEAMFORMEE]',
            AC_CAPABILITY_BF_ANTENNA_2: '[BF-ANTENNA-2]',
            AC_CAPABILITY_SOUNDING_DIMENSION_2: '[SOUNDING-DIMENSION-2]',
            AC_CAPABILITY_MU_BEAMFORMER: '[MU-BEAMFORMER]',
            AC_CAPABILITY_MU_BEAMFORMEE: '[MU-BEAMFORMEE]',
            AC_CAPABILITY_VHT_TXOP_PS: '[VHT-TXOP-PS]',
            AC_CAPABILITY_HTC_VHT: '[HTC-VHT]',
            AC_CAPABILITY_MAX_A_MPDU_LEN_EXP0: '[MAX-A-MPDU-LEN-EXP0]',
            AC_CAPABILITY_MAX_A_MPDU_LEN_EXP1: '[MAX-A-MPDU-LEN-EXP1]',
            AC_CAPABILITY_MAX_A_MPDU_LEN_EXP2: '[MAX-A-MPDU-LEN-EXP2]',
            AC_CAPABILITY_MAX_A_MPDU_LEN_EXP3: '[MAX-A-MPDU-LEN-EXP3]',
            AC_CAPABILITY_MAX_A_MPDU_LEN_EXP4: '[MAX-A-MPDU-LEN-EXP4]',
            AC_CAPABILITY_MAX_A_MPDU_LEN_EXP5: '[MAX-A-MPDU-LEN-EXP5]',
            AC_CAPABILITY_MAX_A_MPDU_LEN_EXP6: '[MAX-A-MPDU-LEN-EXP6]',
            AC_CAPABILITY_MAX_A_MPDU_LEN_EXP7: '[MAX-A-MPDU-LEN-EXP7]',
            AC_CAPABILITY_VHT_LINK_ADAPT2: '[VHT-LINK-ADAPT2]',
            AC_CAPABILITY_VHT_LINK_ADAPT3: '[VHT-LINK-ADAPT3]',
            AC_CAPABILITY_RX_ANTENNA_PATTERN: '[RX-ANTENNA-PATTERN]',
            AC_CAPABILITY_TX_ANTENNA_PATTERN: '[TX-ANTENNA-PATTERN]'}

    HT_CHANNEL_WIDTH_20 = object()
    HT_CHANNEL_WIDTH_40_PLUS = object()
    HT_CHANNEL_WIDTH_40_MINUS = object()

    HT_NAMES = {
        HT_CHANNEL_WIDTH_20: 'HT20',
        HT_CHANNEL_WIDTH_40_PLUS: 'HT40+',
        HT_CHANNEL_WIDTH_40_MINUS: 'HT40-',
    }

    VHT_CHANNEL_WIDTH_20 = object()
    VHT_CHANNEL_WIDTH_40 = object()
    VHT_CHANNEL_WIDTH_80 = object()
    VHT_CHANNEL_WIDTH_160 = object()
    VHT_CHANNEL_WIDTH_80_80 = object()

    # Human readable names for these channel widths.
    VHT_NAMES = {
            VHT_CHANNEL_WIDTH_20: 'VHT20',
            VHT_CHANNEL_WIDTH_40: 'VHT40',
            VHT_CHANNEL_WIDTH_80: 'VHT80',
            VHT_CHANNEL_WIDTH_160: 'VHT160',
            VHT_CHANNEL_WIDTH_80_80: 'VHT80+80',
    }

    # This is a loose merging of the rules for US and EU regulatory
    # domains as taken from IEEE Std 802.11-2016 Appendix E.  For instance,
    # we tolerate HT40 in channels 149-161 (not allowed in EU), but also
    # tolerate HT40+ on channel 7 (not allowed in the US).  We take the loose
    # definition so that we don't prohibit testing in either domain.
    HT40_ALLOW_MAP = {N_CAPABILITY_HT40_MINUS: list(range(5, 14)) +
                                           list(range(40, 65, 8)) +
                                           list(range(104, 145, 8)) +
                                           [153, 161],
                  N_CAPABILITY_HT40_PLUS: list(range(1, 10)) +
                                           list(range(36, 61, 8)) +
                                           list(range(100, 141, 8)) +
                                           [149, 157]}

    PMF_SUPPORT_DISABLED = 0
    PMF_SUPPORT_ENABLED = 1
    PMF_SUPPORT_REQUIRED = 2
    PMF_SUPPORT_VALUES = (PMF_SUPPORT_DISABLED,
                          PMF_SUPPORT_ENABLED,
                          PMF_SUPPORT_REQUIRED)

    DRIVER_NAME = 'nl80211'


    @staticmethod
    def get_channel_for_frequency(frequency):
        """Returns the channel number associated with a given frequency.

        @param value: int frequency in MHz.

        @return int frequency associated with the channel.

        """
        return HostapConfig.CHANNEL_MAP[frequency]


    @staticmethod
    def get_frequency_for_channel(channel):
        """Returns the frequency associated with a given channel number.

        @param value: int channel number.

        @return int frequency in MHz associated with the channel.

        """
        for frequency, channel_iter in six.iteritems(HostapConfig.CHANNEL_MAP):
            if channel == channel_iter:
                return frequency
        else:
            raise error.TestFail('Unknown channel value: %r.' % channel)


    @property
    def _get_default_config(self):
        """@return dict of default options for hostapd."""
        return collections.OrderedDict([
                ('hw_mode', 'g'),
                ('logger_syslog', '-1'),
                ('logger_syslog_level', '0'),
                # default RTS and frag threshold to ``off''
                ('rts_threshold', '-1'),
                ('fragm_threshold', '2346'),
                ('driver', self.DRIVER_NAME)])


    @property
    def _ht40_plus_allowed(self):
        """@return True iff HT40+ is enabled for this configuration."""
        channel_supported = (self.channel in
                             self.HT40_ALLOW_MAP[self.N_CAPABILITY_HT40_PLUS])
        return ((self.N_CAPABILITY_HT40_PLUS in self._n_capabilities or
                 self.N_CAPABILITY_HT40 in self._n_capabilities) and
                channel_supported)


    @property
    def _ht40_minus_allowed(self):
        """@return True iff HT40- is enabled for this configuration."""
        channel_supported = (self.channel in
                             self.HT40_ALLOW_MAP[self.N_CAPABILITY_HT40_MINUS])
        return ((self.N_CAPABILITY_HT40_MINUS in self._n_capabilities or
                 self.N_CAPABILITY_HT40 in self._n_capabilities) and
                channel_supported)


    @property
    def _hostapd_ht_capabilities(self):
        """@return string suitable for the ht_capab= line in a hostapd config"""
        ret = []
        if self._ht40_plus_allowed:
            ret.append('[HT40+]')
        elif self._ht40_minus_allowed:
            ret.append('[HT40-]')
        if self.N_CAPABILITY_GREENFIELD in self._n_capabilities:
            logging.warning('Greenfield flag is ignored for hostap...')
        if self.N_CAPABILITY_SGI20 in self._n_capabilities:
            ret.append('[SHORT-GI-20]')
        if self.N_CAPABILITY_SGI40 in self._n_capabilities:
            ret.append('[SHORT-GI-40]')
        return ''.join(ret)


    @property
    def _hostapd_vht_capabilities(self):
        """@return string suitable for the vht_capab= line in a hostapd config.
        """
        ret = []
        for cap in list(self.AC_CAPABILITIES_MAPPING.keys()):
            if cap in self._ac_capabilities:
                ret.append(self.AC_CAPABILITIES_MAPPING[cap])
        return ''.join(ret)


    @property
    def _require_ht(self):
        """@return True iff clients should be required to support HT."""
        return self._mode == self.MODE_11N_PURE


    @property
    def require_vht(self):
        """@return True iff clients should be required to support VHT."""
        return self._mode == self.MODE_11AC_PURE


    @property
    def _hw_mode(self):
        """@return string hardware mode understood by hostapd."""
        if self._mode == self.MODE_11A:
            return self.MODE_11A
        if self._mode == self.MODE_11B:
            return self.MODE_11B
        if self._mode == self.MODE_11G:
            return self.MODE_11G
        if self._is_11n or self.is_11ac:
            # For their own historical reasons, hostapd wants it this way.
            if self._frequency > 5000:
                return self.MODE_11A

            return self.MODE_11G

        raise error.TestFail('Invalid mode.')

    @property
    def mode(self):
        """@return string hardware mode."""
        return self._mode

    @property
    def channel_width(self):
        """@return object channel width.
        Note: This property ignores legacy rate (e.g., 11g), It will return
              None for these rate.
        """
        ht_channel_width = self._ht_mode
        if self.vht_channel_width is not None:
            if (
                    self.vht_channel_width == self.VHT_CHANNEL_WIDTH_40
                    or self.vht_channel_width == self.VHT_CHANNEL_WIDTH_20):
                if ht_channel_width == self.HT_CHANNEL_WIDTH_20:
                    return self.VHT_CHANNEL_WIDTH_20
            return self.vht_channel_width
        if ht_channel_width:
            return ht_channel_width
        return None

    @property
    def _is_11n(self):
        """@return True iff we're trying to host an 802.11n network."""
        return self._mode in (self.MODE_11N_MIXED, self.MODE_11N_PURE)


    @property
    def is_11ac(self):
        """@return True iff we're trying to host an 802.11ac network."""
        return self._mode in (self.MODE_11AC_MIXED, self.MODE_11AC_PURE)


    @property
    def channel(self):
        """@return int channel number for self.frequency."""
        return self.get_channel_for_frequency(self.frequency)


    @channel.setter
    def channel(self, value):
        """Sets the channel number to configure hostapd to listen on.

        @param value: int channel number.

        """
        self.frequency = self.get_frequency_for_channel(value)


    @property
    def frequency(self):
        """@return int frequency for hostapd to listen on."""
        return self._frequency


    @frequency.setter
    def frequency(self, value):
        """Sets the frequency for hostapd to listen on.

        @param value: int frequency in MHz.

        """
        if value not in self.CHANNEL_MAP or not self.supports_frequency(value):
            raise error.TestFail('Tried to set an invalid frequency: %r.' %
                                 value)

        self._frequency = value


    @property
    def ssid(self):
        """@return string SSID."""
        return self._ssid


    @ssid.setter
    def ssid(self, value):
        """Sets the ssid for the hostapd.

        @param value: string ssid name.

        """
        self._ssid = value


    @property
    def _ht_mode(self):
        """
        @return object one of ( None,
                                HT_CHANNEL_WIDTH_40_PLUS,
                                HT_CHANNEL_WIDTH_40_MINUS,
                                HT_CHANNEL_WIDTH_20)
        """
        if self._is_11n or self.is_11ac:
            if self._ht40_plus_allowed:
                return self.HT_CHANNEL_WIDTH_40_PLUS
            if self._ht40_minus_allowed:
                return self.HT_CHANNEL_WIDTH_40_MINUS
            return self.HT_CHANNEL_WIDTH_20
        return None


    @property
    def packet_capture_mode(self):
        """Get an appropriate packet capture HT/VHT parameter.

        When we go to configure a raw monitor we need to configure
        the phy to listen on the correct channel.  Part of doing
        so is to specify the channel width for HT/VHT channels.  In the
        case that the AP is configured to be either HT40+ or HT40-,
        we could return the wrong parameter because we don't know which
        configuration will be chosen by hostap.

        @return object width_type parameter from packet_capturer.

        """

        if (not self.vht_channel_width
                    or self.vht_channel_width == self.VHT_CHANNEL_WIDTH_40
                    or self.vht_channel_width == self.VHT_CHANNEL_WIDTH_20):
            # if it is VHT40, capture packets on the correct 40MHz band since
            # for packet capturing purposes, only the channel width matters
            ht_mode = self._ht_mode
            if ht_mode == self.HT_CHANNEL_WIDTH_40_PLUS:
                return packet_capturer.WIDTH_HT40_PLUS
            if ht_mode == self.HT_CHANNEL_WIDTH_40_MINUS:
                return packet_capturer.WIDTH_HT40_MINUS
            if ht_mode == self.HT_CHANNEL_WIDTH_20:
                return packet_capturer.WIDTH_HT20

        if self.vht_channel_width == self.VHT_CHANNEL_WIDTH_80:
            return packet_capturer.WIDTH_VHT80
        if self.vht_channel_width == self.VHT_CHANNEL_WIDTH_160:
            return packet_capturer.WIDTH_VHT160
        if self.vht_channel_width == self.VHT_CHANNEL_WIDTH_80_80:
            return packet_capturer.WIDTH_VHT80_80
        return None


    @property
    def perf_loggable_description(self):
        """@return string test description suitable for performance logging."""
        mode = 'mode%s' % (
                self.printable_mode.replace('+', 'p').replace('-', 'm'))
        channel = 'ch%03d' % self.channel
        return '_'.join([channel, mode, self._security_config.security])


    @property
    def printable_mode(self):
        """@return human readable mode string."""

        if self.vht_channel_width is not None:
            return self.VHT_NAMES[self.vht_channel_width]

        ht_mode = self._ht_mode
        if ht_mode:
            return self.HT_NAMES[ht_mode]

        return '11' + self._hw_mode.upper()


    @property
    def ssid_suffix(self):
        """@return meaningful suffix for SSID."""
        return 'ch%d' % self.channel


    @property
    def security_config(self):
        """@return SecurityConfig security config object"""
        return self._security_config


    @property
    def hide_ssid(self):
        """@return bool _hide_ssid flag."""
        return self._hide_ssid


    @property
    def scenario_name(self):
        """@return string _scenario_name value, or None."""
        return self._scenario_name


    @property
    def min_streams(self):
        """@return int _min_streams value, or None."""
        return self._min_streams


    @property
    def frag_threshold(self):
        """@return int frag threshold value, or None."""
        return self._frag_threshold

    @property
    def bridge(self):
        """@return string _bridge value, or None."""
        return self._bridge

    @property
    def max_stas(self):
        """@return int _max_stas value, or None."""
        return self._max_stas

    @property
    def supported_rates(self):
        """@return list of supported bitrates (in Mbps, or None if not
           specified.
        """
        return self._supported_rates

    def __init__(self, mode=MODE_11B, channel=None, frequency=None,
                 n_capabilities=None, hide_ssid=None, beacon_interval=None,
                 dtim_period=None, frag_threshold=None, ssid=None, bssid=None,
                 force_wmm=None, security_config=None,
                 pmf_support=PMF_SUPPORT_DISABLED,
                 obss_interval=None,
                 vht_channel_width=None,
                 vht_center_channel=None,
                 ac_capabilities=None,
                 spectrum_mgmt_required=None,
                 scenario_name=None,
                 supported_rates=None,
                 basic_rates=None,
                 min_streams=None,
                 nas_id=None,
                 mdid=None,
                 r1kh_id=None,
                 r0kh=None,
                 r1kh=None,
                 bridge=None,
                 max_stas=None):
        """Construct a HostapConfig.

        You may specify channel or frequency, but not both.  Both options
        are checked for validity (i.e. you can't specify an invalid channel
        or a frequency that will not be accepted).

        According to IEEE80211ac, both HT and VHT channel width fields must
        be used to select the desired VHT channel width. Refer to IEEE 80211ac
        Tables (VHT Operation Information subfields) and VHT BSS operating
        channel width.

        @param mode string MODE_11x defined above.
        @param channel int channel number.
        @param frequency int frequency of channel.
        @param n_capabilities list of N_CAPABILITY_x defined above.
        @param hide_ssid True if we should set up a hidden SSID.
        @param beacon_interval int beacon interval of AP.
        @param dtim_period int include a DTIM every |dtim_period| beacons.
        @param frag_threshold int maximum outgoing data frame size.
        @param ssid string up to 32 byte SSID overriding the router default.
        @param bssid string like 00:11:22:33:44:55.
        @param force_wmm True if we should force WMM on, False if we should
            force it off, None if we shouldn't force anything.
        @param security_config SecurityConfig object.
        @param pmf_support one of PMF_SUPPORT_* above.  Controls whether the
            client supports/must support 802.11w.
        @param obss_interval int interval in seconds that client should be
            required to do background scans for overlapping BSSes.
        @param vht_channel_width object channel width
        @param vht_center_channel int center channel of segment 0.
        @param ac_capabilities list of AC_CAPABILITY_x defined above.
        @param spectrum_mgmt_required True if we require the DUT to support
            spectrum management.
        @param scenario_name string to be included in file names, instead
            of the interface name.
        @param supported_rates list of rates (in Mbps) that the AP should
             advertise.
        @param basic_rates list of basic rates (in Mbps) that the AP should
            advertise.
        @param min_streams int number of spatial streams required.
        @param nas_id string for RADIUS messages (needed for 802.11r)
        @param mdid string used to indicate a group of APs for FT
        @param r1kh_id string PMK-R1 key holder id for FT
        @param r0kh string R0KHs in the same mobility domain
        @param r1kh string R1KHs in the same mobility domain
        @param bridge string bridge interface to use, if any
        @param max_stas int maximum number of STAs allowed to connect to AP.

        """
        super(HostapConfig, self).__init__()
        if channel is not None and frequency is not None:
            raise error.TestError('Specify either frequency or channel '
                                  'but not both.')

        if n_capabilities is None:
            n_capabilities = []
        if ac_capabilities is None:
            ac_capabilities = []
        self._wmm_enabled = False
        unknown_caps = [cap for cap in n_capabilities
                        if cap not in self.ALL_N_CAPABILITIES]
        if unknown_caps:
            raise error.TestError('Unknown capabilities: %r' % unknown_caps)

        self._n_capabilities = set(n_capabilities)
        if self._n_capabilities:
            self._wmm_enabled = True
        if self._n_capabilities and mode is None:
            mode = self.MODE_11N_PURE
        self._mode = mode

        self._frequency = None
        if channel:
            self.channel = channel
        elif frequency:
            self.frequency = frequency
        else:
            raise error.TestError('Specify either frequency or channel.')

        if not self.supports_frequency(self.frequency):
            raise error.TestFail('Configured a mode %s that does not support '
                                 'frequency %d' % (self._mode, self.frequency))

        self._hide_ssid = hide_ssid
        self._beacon_interval = beacon_interval
        self._dtim_period = dtim_period
        self._frag_threshold = frag_threshold
        if ssid and len(ssid) > 32:
            raise error.TestFail('Tried to specify SSID that was too long.')

        self._ssid = ssid
        self._bssid = bssid
        if force_wmm is not None:
            self._wmm_enabled = force_wmm
        if pmf_support not in self.PMF_SUPPORT_VALUES:
            raise error.TestFail('Invalid value for pmf_support: %r' %
                                 pmf_support)

        self._pmf_support = pmf_support
        self._security_config = (copy.copy(security_config) or
                                xmlrpc_security_types.SecurityConfig())
        self._obss_interval = obss_interval
        if (vht_channel_width == self.VHT_CHANNEL_WIDTH_40
                    or vht_channel_width == self.VHT_CHANNEL_WIDTH_20):
            self._vht_oper_chwidth = 0
        elif vht_channel_width == self.VHT_CHANNEL_WIDTH_80:
            self._vht_oper_chwidth = 1
        elif vht_channel_width == self.VHT_CHANNEL_WIDTH_160:
            self._vht_oper_chwidth = 2
        elif vht_channel_width == self.VHT_CHANNEL_WIDTH_80_80:
            self._vht_oper_chwidth = 3
        elif vht_channel_width is not None:
            raise error.TestFail('Invalid channel width')
        self.vht_channel_width = vht_channel_width
        # TODO(zqiu) Add checking for center channel based on the channel width
        # and operating channel.
        self._vht_oper_centr_freq_seg0_idx = vht_center_channel
        self._ac_capabilities = set(ac_capabilities)
        self._spectrum_mgmt_required = spectrum_mgmt_required
        self._scenario_name = scenario_name
        self._supported_rates = supported_rates
        self._basic_rates = basic_rates
        self._min_streams = min_streams
        self._nas_id = nas_id
        self._mdid = mdid
        self._r1kh_id = r1kh_id
        self._r0kh = r0kh
        self._r1kh = r1kh
        self._bridge = bridge
        # keep _max_stas in [0, 2007], as valid AIDs must be in [1, 2007]
        if max_stas is None:
            self._max_stas = None
        else:
            self._max_stas = max(0, min(max_stas, 2007))


    def __repr__(self):
        return ('%s(mode=%r, channel=%r, frequency=%r, '
                'n_capabilities=%r, hide_ssid=%r, beacon_interval=%r, '
                'dtim_period=%r, frag_threshold=%r, ssid=%r, bssid=%r, '
                'wmm_enabled=%r, security_config=%r, '
                'spectrum_mgmt_required=%r)' % (
                        self.__class__.__name__,
                        self._mode,
                        self.channel,
                        self.frequency,
                        self._n_capabilities,
                        self._hide_ssid,
                        self._beacon_interval,
                        self._dtim_period,
                        self._frag_threshold,
                        self._ssid,
                        self._bssid,
                        self._wmm_enabled,
                        self._security_config,
                        self._spectrum_mgmt_required))


    def supports_channel(self, value):
        """Check whether channel is supported by the current hardware mode.

        @param value: int channel to check.
        @return True iff the current mode supports the band of the channel.

        """
        for freq, channel in six.iteritems(self.CHANNEL_MAP):
            if channel == value:
                return self.supports_frequency(freq)

        return False


    def supports_frequency(self, frequency):
        """Check whether frequency is supported by the current hardware mode.

        @param frequency: int frequency to check.
        @return True iff the current mode supports the band of the frequency.

        """
        if self._mode == self.MODE_11A and frequency < 5000:
            return False

        if self._mode in (self.MODE_11B, self.MODE_11G) and frequency > 5000:
            return False

        if frequency not in self.CHANNEL_MAP:
            return False

        channel = self.CHANNEL_MAP[frequency]
        supports_plus = (channel in
                         self.HT40_ALLOW_MAP[self.N_CAPABILITY_HT40_PLUS])
        supports_minus = (channel in
                          self.HT40_ALLOW_MAP[self.N_CAPABILITY_HT40_MINUS])
        if (self.N_CAPABILITY_HT40_PLUS in self._n_capabilities and
                not supports_plus):
            return False

        if (self.N_CAPABILITY_HT40_MINUS in self._n_capabilities and
                not supports_minus):
            return False

        if (self.N_CAPABILITY_HT40 in self._n_capabilities and
                not supports_plus and not supports_minus):
            return False

        return True


    def generate_dict(self, interface, control_interface, ssid):
        """Generate config dictionary.

        Generate config dictionary for the given |interface|.

        @param interface: string interface to generate config dict for.
        @param control_interface: string control interface
        @param ssid: string SSID of the AP.
        @return dict of hostap configurations.

        """
        # Start with the default config parameters.
        conf = self._get_default_config
        conf['ssid'] = (self._ssid or ssid)
        if self._bssid:
            conf['bssid'] = self._bssid
        conf['channel'] = self.channel
        conf['hw_mode'] = self._hw_mode

        # hostapd specifies rates in units of 100Kbps.
        rate_to_100kbps = lambda rate: str(int(rate * 10))
        if self._supported_rates:
            conf['supported_rates'] = ' '.join(map(rate_to_100kbps,
                                                   self._supported_rates))
        if self._basic_rates:
            conf['basic_rates'] = ' '.join(map(rate_to_100kbps,
                                               self._basic_rates))

        if self._hide_ssid:
            conf['ignore_broadcast_ssid'] = 1
        if self._is_11n or self.is_11ac:
            conf['ieee80211n'] = 1
            conf['ht_capab'] = self._hostapd_ht_capabilities
        if self.is_11ac:
            conf['ieee80211ac'] = 1
            conf['vht_oper_chwidth'] = self._vht_oper_chwidth
            if self._vht_oper_centr_freq_seg0_idx is not None:
                conf['vht_oper_centr_freq_seg0_idx'] = \
                        self._vht_oper_centr_freq_seg0_idx
            conf['vht_capab'] = self._hostapd_vht_capabilities
        if self._wmm_enabled:
            conf['wmm_enabled'] = 1
        if self._require_ht:
            conf['require_ht'] = 1
        if self.require_vht:
            conf['require_vht'] = 1
        if self._beacon_interval:
            conf['beacon_int'] = self._beacon_interval
        if self._dtim_period:
            conf['dtim_period'] = self._dtim_period
        if self._frag_threshold:
            conf['fragm_threshold'] = self._frag_threshold
        if self._pmf_support:
            conf['ieee80211w'] = self._pmf_support
        if self._obss_interval:
            conf['obss_interval'] = self._obss_interval
        if self._nas_id:
            conf['nas_identifier'] = self._nas_id
        if self._mdid:
            conf['mobility_domain'] = self._mdid
        if self._r1kh_id:
            conf['r1_key_holder'] = self._r1kh_id
        if self._r0kh:
            conf['r0kh'] = self._r0kh
        if self._r1kh:
            conf['r1kh'] = self._r1kh
        if self._bridge:
            conf['bridge'] = self._bridge
        if self._max_stas is not None:
            conf['max_num_sta'] = self._max_stas
        conf['interface'] = interface
        conf['ctrl_interface'] = control_interface
        if self._spectrum_mgmt_required:
            # To set spectrum_mgmt_required, we must first set
            # local_pwr_constraint. And to set local_pwr_constraint,
            # we must first set ieee80211d. And to set ieee80211d, ...
            # Point being: order matters here.
            conf['country_code'] = 'US'  # Required for local_pwr_constraint
            conf['ieee80211d'] = 1  # Required for local_pwr_constraint
            conf['local_pwr_constraint'] = 0  # No local constraint
            conf['spectrum_mgmt_required'] = 1  # Requires local_pwr_constraint
        conf.update(self._security_config.get_hostapd_config())
        return conf
