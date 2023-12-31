import unittest

import common

from autotest_lib.server.cros import cfm_jmidata_log_collector


class CfmJmidataLogCollectorTest(unittest.TestCase):
    """
    Tests for the CfmJmidataLogCOllector.
    """
    # Test methods, disable missing-docstring
    # pylint: disable=missing-docstring
    def jmi_get_no_error(self, key):
        data = cfm_jmidata_log_collector.GetDataFromLogs(self, key, JMI_LOG)

    def test_jmi_data(self):
        # TODO(kerl): Add assertions on the actual values.
        self.jmi_get_no_error('video_sent_bytes')
        self.jmi_get_no_error('video_received_bytes')
        self.jmi_get_no_error('audio_sent_bytes')
        self.jmi_get_no_error('audio_received_bytes')
        self.jmi_get_no_error('audio_received_energy_level')
        self.jmi_get_no_error('audio_sent_energy_level')
        self.jmi_get_no_error('framerate_received')
        self.jmi_get_no_error('framerate_sent')
        self.jmi_get_no_error('framerate_decoded')
        self.jmi_get_no_error('frames_decoded')
        self.jmi_get_no_error('frames_encoded')
        self.jmi_get_no_error('average_encode_time')
        self.jmi_get_no_error('framerate_to_renderer')
        self.jmi_get_no_error('framerate_outgoing')
        self.jmi_get_no_error('video_sent_frame_width')
        self.jmi_get_no_error('video_received_frame_width')
        self.jmi_get_no_error('video_sent_frame_height')
        self.jmi_get_no_error('video_received_frame_height')
        self.jmi_get_no_error('cpu_adaptation')
        self.jmi_get_no_error('bandwidth_adaptation')
        self.jmi_get_no_error('adaptation_changes')
        self.jmi_get_no_error('video_packets_sent')
        self.jmi_get_no_error('video_packets_lost')
        self.jmi_get_no_error('video_encode_cpu_usage')
        self.jmi_get_no_error('num_active_vid_in_streams')
        self.jmi_get_no_error('cpu_processors')
        self.jmi_get_no_error('cpu_percent')
        self.jmi_get_no_error('browser_cpu_percent')
        self.jmi_get_no_error('gpu_cpu_percent')
        self.jmi_get_no_error('nacl_effects_cpu_percent')
        self.jmi_get_no_error('renderer_cpu_percent')

if __name__ == '__main__':
    unittest.main()


JMI_LOG = (
'[15.269s]8 talk.media.webrtc.FluteSession: Sending ["jmidatav3","muvc-priva'
'te-chat-9999i7ng7stjkncgvef6rsa76e44sme@groupchat.google.com","c24448231337'
'9091599_NMS",0,{"global":{"jmiVersion":3,"numOfProcessors":4,"totalCpuUsage'
'":118.68242938362964,"tabCpuUsage":86.59793314433666,"browserCpuUsage":19.1'
'03619952307305,"gpuCpuUsage":37.19470138265887}},null,{"VideoBwe":{"googAct'
'ualEncBitrate":1374400,"googAvailableSendBandwidth":3956329,"googRetransmit'
'Bitrate":0,"googAvailableReceiveBandwidth":0,"googTargetEncBitrate":3400000'
',"googBucketDelay":0,"googTransmitBitrate":1389144}},null,{"googCandidatePa'
'ir":{"responsesSent":0,"requestsReceived":0,"googRemoteCandidateType":"loca'
'l","googReadable":"true","googLocalAddress":"[2620:0:1000:1309:3d4f:37b0:65'
'9a:5378]:55946","consentRequestsSent":1,"googTransportType":"udp","googChan'
'nelId":"Channel-audio-1","googLocalCandidateType":"local","googWritable":"t'
'rue","requestsSent":8,"googRemoteAddress":"[2607:f8b0:400e:c05::7f]:19305",'
'"googRtt":20,"googActiveConnection":"true","packetsDiscardedOnSend":0,"byte'
'sReceived":5593200,"responsesReceived":8,"remoteCandidateId":"Cand-ztHGWbjH'
'","localCandidateId":"Cand-iAXjSqBm","bytesSent":1915475,"packetsSent":2367'
'}},{"localcandidate":{"networkType":"unknown","id":"Cand-iAXjSqBm"}},null,{'
'"googCandidatePair":{"responsesSent":0,"requestsReceived":0,"googRemoteCand'
'idateType":"local","googReadable":"false","googLocalAddress":"172.27.213.80'
':55830","consentRequestsSent":0,"googTransportType":"udp","googChannelId":"'
'Channel-audio-1","googLocalCandidateType":"local","googWritable":"false","r'
'equestsSent":0,"googRemoteAddress":"173.194.203.127:19305","googRtt":3000,"'
'googActiveConnection":"false","packetsDiscardedOnSend":0,"bytesReceived":0,'
'"responsesReceived":0,"remoteCandidateId":"Cand-DcRzosft","localCandidateId'
'":"Cand-t/EGflq7","bytesSent":0,"packetsSent":0}},{"localcandidate":{"netwo'
'rkType":"unknown","id":"Cand-t/EGflq7"}},null,null,null,null,null,null,null'
',null,null,null,null,null,null,null,null,null,null,null,null,null,null,{"ss'
'rc":{"googDecodingCTN":958,"packetsLost":0,"googSecondaryDecodedRate":0,"go'
'ogDecodingPLC":3,"packetsReceived":1,"googExpandRate":0,"googJitterReceived'
'":0,"googDecodingCNG":847,"ssrc":181174217,"googPreferredJitterBufferMs":0,'
'"googSpeechExpandRate":0,"totalSamplesDuration":9.58,"totalAudioEnergy":0,"'
'transportId":"Channel-audio-1","mediaType":"audio","googDecodingPLCCNG":108'
',"googCodecName":"opus","googSecondaryDiscardedRate":0,"googDecodingNormal"'
':0,"googTrackId":"hangout7F4822D4_ephemeral.id.google.com%5Efd2eefeedc/1811'
'74217","audioOutputLevel":0,"googAccelerateRate":0,"bytesReceived":21,"goog'
'CurrentDelayMs":10,"googDecodingCTSG":0,"googCaptureStartNtpTimeMs":0,"goog'
'PreemptiveExpandRate":0,"googJitterBufferMs":10,"googDecodingMuted":107}},{'
'"ssrc":{"googDecodingCTN":958,"packetsLost":0,"googSecondaryDecodedRate":0,'
'"googDecodingPLC":4,"packetsReceived":46,"googExpandRate":0,"googJitterRece'
'ived":1,"googDecodingCNG":914,"ssrc":1176043198,"googPreferredJitterBufferM'
's":20,"googSpeechExpandRate":0,"totalSamplesDuration":9.58,"totalAudioEnerg'
'y":8.7084e-9,"transportId":"Channel-audio-1","mediaType":"audio","googDecod'
'ingPLCCNG":10,"googCodecName":"opus","googSecondaryDiscardedRate":0,"googDe'
'codingNormal":30,"googTrackId":"hangoutF18BB657_ephemeral.id.google.com%5Ed'
'5e1b749e1/1176043198","audioOutputLevel":1,"googAccelerateRate":0,"bytesRec'
'eived":1679,"googCurrentDelayMs":89,"googDecodingCTSG":0,"googCaptureStartN'
'tpTimeMs":0,"googPreemptiveExpandRate":0,"googJitterBufferMs":90,"googDecod'
'ingMuted":9}},{"ssrc":{"googDecodingCTN":958,"packetsLost":0,"googSecondary'
'DecodedRate":0,"googDecodingPLC":3,"packetsReceived":46,"googExpandRate":0,'
'"googJitterReceived":2,"googDecodingCNG":919,"ssrc":1301596892,"googPreferr'
'edJitterBufferMs":20,"googSpeechExpandRate":0,"totalSamplesDuration":9.58,"'
'totalAudioEnergy":8.81085e-9,"transportId":"Channel-audio-1","mediaType":"a'
'udio","googDecodingPLCCNG":6,"googCodecName":"opus","googSecondaryDiscarded'
'Rate":0,"googDecodingNormal":30,"googTrackId":"hangoutF724C8C9_ephemeral.id'
'.google.com%5E23a2bf2b70/1301596892","audioOutputLevel":1,"googAccelerateRa'
'te":0,"bytesReceived":1679,"googCurrentDelayMs":80,"googDecodingCTSG":0,"go'
'ogCaptureStartNtpTimeMs":0,"googPreemptiveExpandRate":0,"googJitterBufferMs'
'":80,"googDecodingMuted":5}},{"ssrc":{"googDecodingCTN":958,"packetsLost":0'
',"googSecondaryDecodedRate":0,"googDecodingPLC":3,"packetsReceived":1,"goog'
'ExpandRate":0,"googJitterReceived":0,"googDecodingCNG":847,"ssrc":226549935'
'5,"googPreferredJitterBufferMs":0,"googSpeechExpandRate":0,"totalSamplesDur'
'ation":9.58,"totalAudioEnergy":0,"transportId":"Channel-audio-1","mediaType'
'":"audio","googDecodingPLCCNG":108,"googCodecName":"opus","googSecondaryDis'
'cardedRate":0,"googDecodingNormal":0,"googTrackId":"hangout83754FD7_ephemer'
'al.id.google.com%5E2f542d3bca/2265499355","audioOutputLevel":0,"googAcceler'
'ateRate":0,"bytesReceived":21,"googCurrentDelayMs":10,"googDecodingCTSG":0,'
'"googCaptureStartNtpTimeMs":0,"googPreemptiveExpandRate":0,"googJitterBuffe'
'rMs":10,"googDecodingMuted":107}},{"ssrc":{"googDecodingCTN":958,"packetsLo'
'st":0,"googSecondaryDecodedRate":0,"googDecodingPLC":0,"packetsReceived":46'
',"googJitterReceived":1,"googDecodingCNG":929,"ssrc":3200798097,"googPrefer'
'redJitterBufferMs":20,"googSpeechExpandRate":0,"totalSamplesDuration":9.58,'
'"totalAudioEnergy":8.81085e-9,"transportId":"Channel-audio-1","mediaType":"'
'audio","googDecodingPLCCNG":0,"googCodecName":"opus","googSecondaryDiscarde'
'dRate":0,"googDecodingNormal":29,"googTrackId":"hangout2A69C3CE_ephemeral.i'
'd.google.com%5E51a54492e6/3200798097","audioOutputLevel":1,"googAccelerateR'
'ate":0,"bytesReceived":1679,"googCurrentDelayMs":81,"googDecodingCTSG":0,"g'
'oogExpandRate":0,"googPreemptiveExpandRate":0,"googJitterBufferMs":93,"goog'
'DecodingMuted":0}},{"ssrc":{"googDecodingCTN":958,"packetsLost":0,"googSeco'
'ndaryDecodedRate":0,"googDecodingPLC":3,"packetsReceived":15,"googExpandRat'
'e":0,"googJitterReceived":5,"googDecodingCNG":895,"ssrc":3611799563,"googPr'
'eferredJitterBufferMs":20,"googSpeechExpandRate":0,"totalSamplesDuration":9'
'.58,"totalAudioEnergy":0,"transportId":"Channel-audio-1","mediaType":"audio'
'","googDecodingPLCCNG":60,"googCodecName":"opus","googSecondaryDiscardedRat'
'e":0,"googDecodingNormal":0,"googTrackId":"hangoutF9EAE779_ephemeral.id.goo'
'gle.com%5E42ce92f210/3611799563","audioOutputLevel":0,"googAccelerateRate":'
'0,"bytesReceived":315,"googCurrentDelayMs":10,"googDecodingCTSG":0,"googCap'
'tureStartNtpTimeMs":0,"googPreemptiveExpandRate":0,"googJitterBufferMs":90,'
'"googDecodingMuted":59}},{"ssrc":{"googCaptureStartNtpTimeMs":0,"packetsLos'
't":0,"googSecondaryDecodedRate":0,"googDecodingPLC":0,"packetsReceived":0,"'
'googJitterReceived":0,"googDecodingCNG":0,"ssrc":3678956685,"googPreferredJ'
'itterBufferMs":0,"googSpeechExpandRate":0,"totalSamplesDuration":0,"totalAu'
'dioEnergy":0,"transportId":"Channel-audio-1","mediaType":"audio","googDecod'
'ingCTN":0,"googDecodingPLCCNG":0,"googCodecName":"","googDecodingNormal":0,'
'"googTrackId":"hangout9227780C_ephemeral.id.google.com%5Effba3cb857/3678956'
'685","googSecondaryDiscardedRate":0,"googAccelerateRate":0,"bytesReceived":'
'0,"googCurrentDelayMs":0,"googDecodingCTSG":0,"googExpandRate":0,"googPreem'
'ptiveExpandRate":0,"googJitterBufferMs":0,"googDecodingMuted":0}},{"ssrc":{'
'"googDecodingCTN":958,"packetsLost":0,"googSecondaryDecodedRate":0,"googDec'
'odingPLC":3,"packetsReceived":46,"googExpandRate":0,"googJitterReceived":2,'
'"googDecodingCNG":916,"ssrc":3763354856,"googPreferredJitterBufferMs":20,"g'
'oogSpeechExpandRate":0,"totalSamplesDuration":9.58,"totalAudioEnergy":8.801'
'54e-9,"transportId":"Channel-audio-1","mediaType":"audio","googDecodingPLCC'
'NG":9,"googCodecName":"opus","googSecondaryDiscardedRate":0,"googDecodingNo'
'rmal":30,"googTrackId":"hangout757A0188_ephemeral.id.google.com%5E576980a8a'
'e/3763354856","audioOutputLevel":1,"googAccelerateRate":0,"bytesReceived":1'
'679,"googCurrentDelayMs":91,"googDecodingCTSG":0,"googCaptureStartNtpTimeMs'
'":0,"googPreemptiveExpandRate":0,"googJitterBufferMs":90,"googDecodingMuted'
'":8}},{"ssrc":{"googDecodingCTN":958,"packetsLost":0,"googSecondaryDecodedR'
'ate":0,"googDecodingPLC":0,"packetsReceived":46,"googJitterReceived":3,"goo'
'gDecodingCNG":928,"ssrc":4088893509,"googPreferredJitterBufferMs":20,"googS'
'peechExpandRate":0,"totalSamplesDuration":9.58,"totalAudioEnergy":8.80154e-'
'9,"transportId":"Channel-audio-1","mediaType":"audio","googDecodingPLCCNG":'
'0,"googCodecName":"opus","googSecondaryDiscardedRate":0,"googDecodingNormal'
'":30,"googTrackId":"hangoutD84553B2_ephemeral.id.google.com%5E63d56c7215/40'
'88893509","audioOutputLevel":1,"googAccelerateRate":0,"bytesReceived":1748,'
'"googCurrentDelayMs":89,"googDecodingCTSG":0,"googExpandRate":0,"googPreemp'
'tiveExpandRate":0,"googJitterBufferMs":90,"googDecodingMuted":0}},{"ssrc":{'
'"googContentType":"realtime","googCaptureStartNtpTimeMs":0,"googTargetDelay'
'Ms":127,"packetsLost":0,"googDecodeMs":6,"googFrameHeightReceived":180,"goo'
'gFrameRateOutput":23,"packetsReceived":193,"ssrc":234375295,"googRenderDela'
'yMs":10,"googMaxDecodeMs":13,"googTrackId":"hangout7F4822D4_ephemeral.id.go'
'ogle.com%5Efd2eefeedc/234375295","googFrameWidthReceived":320,"codecImpleme'
'ntationName":"unknown","transportId":"Channel-audio-1","mediaType":"video",'
'"googInterframeDelayMax":1541,"googCodecName":"VP8","googFrameRateReceived"'
':25,"framesDecoded":141,"googNacksSent":0,"googFirsSent":0,"bytesReceived":'
'145834,"googCurrentDelayMs":78,"googMinPlayoutDelayMs":0,"googFrameRateDeco'
'ded":23,"googJitterBufferMs":104,"googPlisSent":0,"fpsGraphicsInput":15.331'
'086576691497,"fpsGraphicsOutput":15.331086576691497,"googFrameSpacing":996}'
'},{"ssrc":{"googContentType":"realtime","googCaptureStartNtpTimeMs":0,"goog'
'TargetDelayMs":117,"packetsLost":0,"googDecodeMs":3,"googFrameHeightReceive'
'd":180,"googFrameRateOutput":24,"packetsReceived":223,"ssrc":365354875,"goo'
'gRenderDelayMs":10,"googMaxDecodeMs":14,"googTrackId":"hangout2A69C3CE_ephe'
'meral.id.google.com%5E51a54492e6/365354875","googFrameWidthReceived":320,"c'
'odecImplementationName":"unknown","transportId":"Channel-audio-1","mediaTyp'
'e":"video","googInterframeDelayMax":362,"googCodecName":"VP8","googFrameRat'
'eReceived":23,"framesDecoded":168,"googNacksSent":0,"googFirsSent":0,"bytes'
'Received":171134,"googCurrentDelayMs":75,"googMinPlayoutDelayMs":0,"googFra'
'meRateDecoded":24,"googJitterBufferMs":93,"googPlisSent":0,"fpsGraphicsInpu'
't":17.980188823503184,"fpsGraphicsOutput":17.980188823503184,"googFrameSpac'
'ing":502}},{"ssrc":{"googContentType":"realtime","googCaptureStartNtpTimeMs'
'":0,"googTargetDelayMs":40,"packetsLost":0,"googDecodeMs":10,"googFrameHeig'
'htReceived":180,"googFrameRateOutput":13,"packetsReceived":1078,"ssrc":6586'
'85198,"googRenderDelayMs":10,"googMaxDecodeMs":12,"googTrackId":"hangoutD84'
'553B2_ephemeral.id.google.com%5E63d56c7215/658685198","googFrameWidthReceiv'
'ed":320,"codecImplementationName":"unknown","transportId":"Channel-audio-1"'
',"mediaType":"video","googInterframeDelayMax":152,"googCodecName":"VP8","go'
'ogFrameRateReceived":13,"framesDecoded":108,"googNacksSent":0,"googFirsSent'
'":0,"bytesReceived":1232161,"googCurrentDelayMs":40,"googMinPlayoutDelayMs"'
':0,"googFrameRateDecoded":13,"googJitterBufferMs":18,"googPlisSent":0,"fpsG'
'raphicsInput":11.537631880897864,"fpsGraphicsOutput":11.42980354556237,"goo'
'gFrameSpacing":501}},{"ssrc":{"googContentType":"realtime","googCaptureStar'
'tNtpTimeMs":0,"googTargetDelayMs":110,"packetsLost":0,"googDecodeMs":19,"go'
'ogFrameHeightReceived":720,"googFrameRateOutput":23,"packetsReceived":2542,'
'"ssrc":771366933,"googRenderDelayMs":10,"googMaxDecodeMs":22,"googTrackId":'
'"hangoutF18BB657_ephemeral.id.google.com%5Ed5e1b749e1/771366933","googFrame'
'WidthReceived":1280,"codecImplementationName":"unknown","transportId":"Chan'
'nel-audio-1","mediaType":"video","googInterframeDelayMax":136,"googCodecNam'
'e":"VP8","googFrameRateReceived":24,"framesDecoded":203,"googNacksSent":0,"'
'googFirsSent":0,"bytesReceived":2921629,"googCurrentDelayMs":101,"googMinPl'
'ayoutDelayMs":0,"googFrameRateDecoded":23,"googJitterBufferMs":78,"googPlis'
'Sent":0,"fpsGraphicsInput":21.86439764526312,"fpsGraphicsOutput":21.8539465'
'38565932,"googFrameSpacing":512}},{"ssrc":{"googContentType":"realtime","go'
'ogCaptureStartNtpTimeMs":0,"googTargetDelayMs":103,"packetsLost":0,"googDec'
'odeMs":3,"googFrameHeightReceived":180,"googFrameRateOutput":22,"packetsRec'
'eived":195,"ssrc":811526342,"googRenderDelayMs":10,"googMaxDecodeMs":13,"go'
'ogTrackId":"hangoutF724C8C9_ephemeral.id.google.com%5E23a2bf2b70/811526342"'
',"googFrameWidthReceived":320,"codecImplementationName":"unknown","transpor'
'tId":"Channel-audio-1","mediaType":"video","googInterframeDelayMax":1444,"g'
'oogCodecName":"VP8","googFrameRateReceived":23,"framesDecoded":143,"googNac'
'ksSent":0,"googFirsSent":0,"bytesReceived":147248,"googCurrentDelayMs":69,"'
'googMinPlayoutDelayMs":0,"googFrameRateDecoded":22,"googJitterBufferMs":80,'
'"googPlisSent":0,"fpsGraphicsInput":15.432764893113935,"fpsGraphicsOutput":'
'15.432764893113935,"googFrameSpacing":505}},{"ssrc":{"googContentType":"rea'
'ltime","googCaptureStartNtpTimeMs":0,"googTargetDelayMs":92,"packetsLost":0'
',"googDecodeMs":4,"googFrameHeightReceived":180,"googFrameRateOutput":21,"p'
'acketsReceived":208,"ssrc":1009208770,"googRenderDelayMs":10,"googMaxDecode'
'Ms":13,"googTrackId":"hangout757A0188_ephemeral.id.google.com%5E576980a8ae/'
'1009208770","googFrameWidthReceived":320,"codecImplementationName":"unknown'
'","transportId":"Channel-audio-1","mediaType":"video","googInterframeDelayM'
'ax":810,"googCodecName":"VP8","googFrameRateReceived":23,"framesDecoded":15'
'6,"googNacksSent":0,"googFirsSent":0,"bytesReceived":151801,"googCurrentDel'
'ayMs":79,"googMinPlayoutDelayMs":0,"googFrameRateDecoded":21,"googJitterBuf'
'ferMs":69,"googPlisSent":0,"fpsGraphicsInput":16.79306605413843,"fpsGraphic'
'sOutput":16.684723692498824,"googFrameSpacing":502}},{"ssrc":{"googContentT'
'ype":"realtime","googCaptureStartNtpTimeMs":0,"googTargetDelayMs":82,"packe'
'tsLost":0,"googDecodeMs":4,"googFrameHeightReceived":180,"googFrameRateOutp'
'ut":24,"packetsReceived":210,"ssrc":1750328988,"googRenderDelayMs":10,"goog'
'MaxDecodeMs":15,"googTrackId":"hangout9227780C_ephemeral.id.google.com%5Eff'
'ba3cb857/1750328988","googFrameWidthReceived":320,"codecImplementationName"'
':"unknown","transportId":"Channel-audio-1","mediaType":"video","googInterfr'
'ameDelayMax":518,"googCodecName":"VP8","googFrameRateReceived":24,"framesDe'
'coded":152,"googNacksSent":0,"googFirsSent":0,"bytesReceived":157397,"googC'
'urrentDelayMs":72,"googMinPlayoutDelayMs":0,"googFrameRateDecoded":24,"goog'
'JitterBufferMs":57,"googPlisSent":0,"fpsGraphicsInput":16.296136355665766,"'
'fpsGraphicsOutput":16.296136355665766,"googFrameSpacing":746}},{"ssrc":{"go'
'ogContentType":"realtime","googCaptureStartNtpTimeMs":0,"googTargetDelayMs"'
':102,"packetsLost":0,"googDecodeMs":4,"googFrameHeightReceived":180,"googFr'
'ameRateOutput":24,"packetsReceived":220,"ssrc":2749867232,"googRenderDelayM'
's":10,"googMaxDecodeMs":12,"googTrackId":"hangoutF9EAE779_ephemeral.id.goog'
'le.com%5E42ce92f210/2749867232","googFrameWidthReceived":320,"codecImplemen'
'tationName":"unknown","transportId":"Channel-audio-1","mediaType":"video","'
'googInterframeDelayMax":583,"googCodecName":"VP8","googFrameRateReceived":2'
'4,"framesDecoded":171,"googNacksSent":0,"googFirsSent":0,"bytesReceived":16'
'3793,"googCurrentDelayMs":75,"googMinPlayoutDelayMs":0,"googFrameRateDecode'
'd":24,"googJitterBufferMs":80,"googPlisSent":0,"fpsGraphicsInput":18.197388'
'58914156,"fpsGraphicsOutput":18.19738858914156,"googFrameSpacing":502}},{"s'
'src":{"googContentType":"realtime","googCaptureStartNtpTimeMs":0,"googTarge'
'tDelayMs":87,"packetsLost":0,"googDecodeMs":5,"googFrameHeightReceived":180'
',"googFrameRateOutput":24,"packetsReceived":220,"ssrc":3276788334,"googRend'
'erDelayMs":10,"googMaxDecodeMs":13,"googTrackId":"hangout83754FD7_ephemeral'
'.id.google.com%5E2f542d3bca/3276788334","googFrameWidthReceived":320,"codec'
'ImplementationName":"unknown","transportId":"Channel-audio-1","mediaType":"'
'video","googInterframeDelayMax":648,"googCodecName":"VP8","googFrameRateRec'
'eived":25,"framesDecoded":167,"googNacksSent":0,"googFirsSent":0,"bytesRece'
'ived":159314,"googCurrentDelayMs":72,"googMinPlayoutDelayMs":0,"googFrameRa'
'teDecoded":24,"googJitterBufferMs":64,"googPlisSent":0,"fpsGraphicsInput":1'
'7.872616766543615,"fpsGraphicsOutput":17.76429787704941,"googFrameSpacing":'
'502}},{"ssrc":{"audioInputLevel":0,"packetsLost":0,"googResidualEchoLikelih'
'ood":0,"googRtt":24,"googResidualEchoLikelihoodRecentMax":0,"googEchoCancel'
'lationReturnLossEnhancement":-100,"totalSamplesDuration":0,"totalAudioEnerg'
'y":0,"transportId":"Channel-audio-1","mediaType":"audio","aecDivergentFilte'
'rFraction":0,"googEchoCancellationReturnLoss":-100,"googCodecName":"opus","'
'googEchoCancellationEchoDelayMedian":388,"googEchoCancellationQualityMin":0'
',"googTrackId":"4224ce1c-0c2f-4bb4-9d91-3798ca549328","ssrc":141808114,"goo'
'gJitterReceived":3,"googTypingNoiseState":"false","packetsSent":63,"bytesSe'
'nt":2709,"googEchoCancellationEchoDelayStdDev":0}},{"ssrc":{"googContentTyp'
'e":"realtime","googFrameWidthSent":1280,"packetsLost":45,"googRtt":23,"goog'
'EncodeUsagePercent":82,"googCpuLimitedResolution":"false","googNacksReceive'
'd":0,"googBandwidthLimitedResolution":"false","googPlisReceived":0,"googAvg'
'EncodeMs":17,"googTrackId":"99a2ae0e-9cfa-4a7d-83a6-8ea3d5cc2c2e","googFram'
'eRateInput":24,"framesEncoded":657,"codecImplementationName":"SimulcastEnco'
'derAdapter (unknown, unknown, unknown)","transportId":"Channel-audio-1","me'
'diaType":"video","googFrameHeightSent":720,"googFrameRateSent":24,"googCode'
'cName":"VP8","googAdaptationChanges":0,"ssrc":3865366536,"googFirsReceived"'
':0,"packetsSent":1869,"bytesSent":1796767,"googAdaptationReason":0}}]')
