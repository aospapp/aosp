/*
 * Copyright 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <audio_utils/ChannelMix.h>

#include <random>
#include <vector>

#include <benchmark/benchmark.h>
#include <log/log.h>

static constexpr audio_channel_mask_t kChannelPositionMasks[] = {
    AUDIO_CHANNEL_OUT_FRONT_LEFT,
    AUDIO_CHANNEL_OUT_FRONT_CENTER,
    AUDIO_CHANNEL_OUT_STEREO,
    AUDIO_CHANNEL_OUT_2POINT1,
    AUDIO_CHANNEL_OUT_2POINT0POINT2,
    AUDIO_CHANNEL_OUT_QUAD, // AUDIO_CHANNEL_OUT_QUAD_BACK
    AUDIO_CHANNEL_OUT_QUAD_SIDE,
    AUDIO_CHANNEL_OUT_SURROUND,
    AUDIO_CHANNEL_OUT_2POINT1POINT2,
    AUDIO_CHANNEL_OUT_3POINT0POINT2,
    AUDIO_CHANNEL_OUT_PENTA,
    AUDIO_CHANNEL_OUT_3POINT1POINT2,
    AUDIO_CHANNEL_OUT_5POINT1, // AUDIO_CHANNEL_OUT_5POINT1_BACK
    AUDIO_CHANNEL_OUT_5POINT1_SIDE,
    AUDIO_CHANNEL_OUT_6POINT1,
    AUDIO_CHANNEL_OUT_5POINT1POINT2,
    AUDIO_CHANNEL_OUT_7POINT1,
    AUDIO_CHANNEL_OUT_5POINT1POINT4,
    AUDIO_CHANNEL_OUT_7POINT1POINT2,
    AUDIO_CHANNEL_OUT_7POINT1POINT4,
    AUDIO_CHANNEL_OUT_13POINT_360RA,
    AUDIO_CHANNEL_OUT_22POINT2,
};

/*
$ adb shell /data/benchmarktest64/channelmix_benchmark/channelmix_benchmark
Pixel 7 arm64 benchmark

-----------------------------------------------------------
Benchmark                 Time             CPU   Iterations
-----------------------------------------------------------
channelmix_benchmark:
  #BM_ChannelMix_Stereo/0             2266 ns     2251 ns       310903
  #BM_ChannelMix_Stereo/1             2262 ns     2251 ns       310898
  #BM_ChannelMix_Stereo/2              255 ns      254 ns      2754285
  #BM_ChannelMix_Stereo/3             2969 ns     2954 ns       235901
  #BM_ChannelMix_Stereo/4             3350 ns     3334 ns       209901
  #BM_ChannelMix_Stereo/5              814 ns      810 ns       863246
  #BM_ChannelMix_Stereo/6              814 ns      810 ns       863255
  #BM_ChannelMix_Stereo/7             3349 ns     3328 ns       210234
  #BM_ChannelMix_Stereo/8             3671 ns     3654 ns       191555
  #BM_ChannelMix_Stereo/9             3680 ns     3654 ns       191583
  #BM_ChannelMix_Stereo/10            3667 ns     3650 ns       191738
  #BM_ChannelMix_Stereo/11            4109 ns     4089 ns       171118
  #BM_ChannelMix_Stereo/12            1209 ns     1203 ns       581812
  #BM_ChannelMix_Stereo/13            1209 ns     1203 ns       581666
  #BM_ChannelMix_Stereo/14            4694 ns     4674 ns       149798
  #BM_ChannelMix_Stereo/15            1306 ns     1301 ns       537843
  #BM_ChannelMix_Stereo/16            1307 ns     1301 ns       537898
  #BM_ChannelMix_Stereo/17            2059 ns     2050 ns       341145
  #BM_ChannelMix_Stereo/18            2053 ns     2043 ns       342709
  #BM_ChannelMix_Stereo/19            2462 ns     2451 ns       285554
  #BM_ChannelMix_Stereo/20            7889 ns     7853 ns        89005
  #BM_ChannelMix_Stereo/21            6133 ns     6104 ns       114499
  #BM_ChannelMix_5Point1/0            1676 ns     1665 ns       420195
  #BM_ChannelMix_5Point1/1            1675 ns     1667 ns       419527
  #BM_ChannelMix_5Point1/2             537 ns      535 ns      1310551
  #BM_ChannelMix_5Point1/3            3039 ns     3024 ns       231306
  #BM_ChannelMix_5Point1/4            3763 ns     3744 ns       186929
  #BM_ChannelMix_5Point1/5             698 ns      695 ns       990457
  #BM_ChannelMix_5Point1/6             661 ns      657 ns      1058724
  #BM_ChannelMix_5Point1/7            3766 ns     3748 ns       186771
  #BM_ChannelMix_5Point1/8            4395 ns     4374 ns       159819
  #BM_ChannelMix_5Point1/9            4389 ns     4369 ns       160144
  #BM_ChannelMix_5Point1/10           4390 ns     4369 ns       160196
  #BM_ChannelMix_5Point1/11           5111 ns     5084 ns       137574
  #BM_ChannelMix_5Point1/12            652 ns      649 ns      1086857
  #BM_ChannelMix_5Point1/13            653 ns      649 ns      1072477
  #BM_ChannelMix_5Point1/14           5762 ns     5734 ns       122129
  #BM_ChannelMix_5Point1/15            778 ns      774 ns       903415
  #BM_ChannelMix_5Point1/16            778 ns      775 ns       903085
  #BM_ChannelMix_5Point1/17           1220 ns     1214 ns       575908
  #BM_ChannelMix_5Point1/18           1015 ns     1006 ns       694142
  #BM_ChannelMix_5Point1/19           1382 ns     1373 ns       509721
  #BM_ChannelMix_5Point1/20          10184 ns    10076 ns        69550
  #BM_ChannelMix_5Point1/21           5401 ns     5362 ns       130580
  #BM_ChannelMix_7Point1/0            1644 ns     1632 ns       428673
  #BM_ChannelMix_7Point1/1            1640 ns     1633 ns       428639
  #BM_ChannelMix_7Point1/2             722 ns      719 ns       973262
  #BM_ChannelMix_7Point1/3            3076 ns     3062 ns       228509
  #BM_ChannelMix_7Point1/4            3902 ns     3884 ns       180207
  #BM_ChannelMix_7Point1/5             727 ns      723 ns       968505
  #BM_ChannelMix_7Point1/6            3905 ns     3886 ns       180132
  #BM_ChannelMix_7Point1/7            3903 ns     3886 ns       180110
  #BM_ChannelMix_7Point1/8            4723 ns     4700 ns       148911
  #BM_ChannelMix_7Point1/9            4727 ns     4704 ns       148850
  #BM_ChannelMix_7Point1/10           4723 ns     4702 ns       148944
  #BM_ChannelMix_7Point1/11           5518 ns     5492 ns       127454
  #BM_ChannelMix_7Point1/12            723 ns      720 ns       971533
  #BM_ChannelMix_7Point1/13           5520 ns     5492 ns       127444
  #BM_ChannelMix_7Point1/14           6299 ns     6270 ns       111619
  #BM_ChannelMix_7Point1/15            561 ns      559 ns      1266804
  #BM_ChannelMix_7Point1/16            563 ns      559 ns      1254781
  #BM_ChannelMix_7Point1/17           1240 ns     1234 ns       561452
  #BM_ChannelMix_7Point1/18           1100 ns     1095 ns       638789
  #BM_ChannelMix_7Point1/19           1525 ns     1518 ns       460122
  #BM_ChannelMix_7Point1/20          10998 ns    10950 ns        63928
  #BM_ChannelMix_7Point1/21           4656 ns     4621 ns       151487
  #BM_ChannelMix_7Point1Point4/0      2301 ns     2290 ns       305500
  #BM_ChannelMix_7Point1Point4/1      2301 ns     2290 ns       305620
  #BM_ChannelMix_7Point1Point4/2       913 ns      908 ns       770049
  #BM_ChannelMix_7Point1Point4/3      4232 ns     4212 ns       166032
  #BM_ChannelMix_7Point1Point4/4      5241 ns     5216 ns       134179
  #BM_ChannelMix_7Point1Point4/5      1084 ns     1079 ns       648761
  #BM_ChannelMix_7Point1Point4/6      5243 ns     5219 ns       134126
  #BM_ChannelMix_7Point1Point4/7      5250 ns     5226 ns       133968
  #BM_ChannelMix_7Point1Point4/8      6225 ns     6194 ns       112973
  #BM_ChannelMix_7Point1Point4/9      6223 ns     6193 ns       112985
  #BM_ChannelMix_7Point1Point4/10     6223 ns     6193 ns       113047
  #BM_ChannelMix_7Point1Point4/11     7416 ns     7380 ns        94840
  #BM_ChannelMix_7Point1Point4/12      903 ns      899 ns       778228
  #BM_ChannelMix_7Point1Point4/13     7414 ns     7380 ns        94835
  #BM_ChannelMix_7Point1Point4/14     8354 ns     8314 ns        84219
  #BM_ChannelMix_7Point1Point4/15      818 ns      815 ns       865119
  #BM_ChannelMix_7Point1Point4/16      820 ns      816 ns       854456
  #BM_ChannelMix_7Point1Point4/17     1106 ns     1100 ns       636240
  #BM_ChannelMix_7Point1Point4/18     1104 ns     1099 ns       636313
  #BM_ChannelMix_7Point1Point4/19     1151 ns     1145 ns       611497
  #BM_ChannelMix_7Point1Point4/20    14454 ns    14385 ns        48561
  #BM_ChannelMix_7Point1Point4/21     5982 ns     5954 ns       117562
*/

template<audio_channel_mask_t OUTPUT_CHANNEL_MASK>
static void BenchmarkChannelMix(benchmark::State& state) {
    const audio_channel_mask_t channelMask = kChannelPositionMasks[state.range(0)];
    using namespace ::android::audio_utils::channels;
    ChannelMix<OUTPUT_CHANNEL_MASK> channelMix(channelMask);
    const size_t outChannels = audio_channel_count_from_out_mask(OUTPUT_CHANNEL_MASK);
    constexpr size_t frameCount = 1024;
    size_t inChannels = audio_channel_count_from_out_mask(channelMask);
    std::vector<float> input(inChannels * frameCount);
    std::vector<float> output(outChannels * frameCount);
    constexpr float amplitude = 0.01f;

    std::minstd_rand gen(channelMask);
    std::uniform_real_distribution<> dis(-amplitude, amplitude);
    for (auto& in : input) {
        in = dis(gen);
    }

    assert(channelMix.getInputChannelMask() != AUDIO_CHANNEL_NONE);
    // Run the test
    for (auto _ : state) {
        benchmark::DoNotOptimize(input.data());
        benchmark::DoNotOptimize(output.data());
        channelMix.process(input.data(), output.data(), frameCount, false /* accumulate */);
        benchmark::ClobberMemory();
    }

    state.SetComplexityN(inChannels);
    state.SetLabel(audio_channel_out_mask_to_string(channelMask));
}

static void BM_ChannelMix_Stereo(benchmark::State& state) {
    BenchmarkChannelMix<AUDIO_CHANNEL_OUT_STEREO>(state);
}

static void BM_ChannelMix_5Point1(benchmark::State& state) {
    BenchmarkChannelMix<AUDIO_CHANNEL_OUT_5POINT1>(state);
}

static void BM_ChannelMix_7Point1(benchmark::State& state) {
    BenchmarkChannelMix<AUDIO_CHANNEL_OUT_7POINT1>(state);
}

static void BM_ChannelMix_7Point1Point4(benchmark::State& state) {
    BenchmarkChannelMix<AUDIO_CHANNEL_OUT_7POINT1POINT4>(state);
}

static void ChannelMixArgs(benchmark::internal::Benchmark* b) {
    for (int i = 0; i < (int)std::size(kChannelPositionMasks); i++) {
        b->Args({i});
    }
}

BENCHMARK(BM_ChannelMix_Stereo)->Apply(ChannelMixArgs);

BENCHMARK(BM_ChannelMix_5Point1)->Apply(ChannelMixArgs);

BENCHMARK(BM_ChannelMix_7Point1)->Apply(ChannelMixArgs);

BENCHMARK(BM_ChannelMix_7Point1Point4)->Apply(ChannelMixArgs);

BENCHMARK_MAIN();
