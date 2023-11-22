# Copyright (c) 2012-2018 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import logging, os, re, threading, time, utils

from autotest_lib.client.bin import test
from autotest_lib.client.common_lib import error
from autotest_lib.client.common_lib.cros import chrome

SYSFS_LOW_MEM_DIR = '/sys/kernel/mm/chromeos-low_mem/'
SYSFS_MARGIN_FILENAME = SYSFS_LOW_MEM_DIR + 'margin'
SYSFS_AVAILABLE_FILENAME = SYSFS_LOW_MEM_DIR + 'available'

PF_MAX_RATE_PERIOD = 5.0  # seconds
PF_RATE_INTERVAL = 10     # seconds

def get_pf_count():
    """Returns the total number of major page faults since boot.
    """
    with open('/proc/vmstat') as f:
        return float(next((name_value for name_value in f.read().split('\n')
                           if 'pgmajfault ' in name_value)).split()[1])

MEMINFO_RE = re.compile("^(\w+):\s+(\d+)")

def get_meminfo():
    """Returns a dict of name-value pairs from /proc/meminfo.
    """
    info = {}
    with open('/proc/meminfo') as f:
        for line in f:
            m = MEMINFO_RE.match(line)
            if m:
                info[m.group(1)] = int(m.group(2))
    return info

class URLList:
    """A list of URLs as strings, plus a method for circular iteration."""
    def __init__(self):
        """Class initializer"""
        self.index = 0

    TAB_URLS = [
                # commented-out URLs go to sign-in page
                # 'https://mail.google.com',
                # 'https://docs.google.com',
                # 'https://calendar.google.com',
                # 'https://keep.google.com',
                # 'https://sites.google.com',
                # 'https://play.google.com/movies',
                'https://drive.google.com',
                'https://photos.google.com',
                'https://news.google.com',
                'https://plus.google.com',
                'https://maps.google.com',
                'https://play.google.com/store',
                'https://play.google.com/music',
                'https://youtube.com',
                'https://www.nytimes.com',
                'https://www.whitehouse.gov',
                'https://www.wsj.com',
                'http://www.newsweek.com',            # seriously, http?
                'https://www.washingtonpost.com',
                'https://www.foxnews.com',
                'https://www.nbc.com',
                'https://www.amazon.com',
                'https://www.walmart.com',
                'https://www.target.com',
                'https://www.facebook.com',
                'https://www.cnn.com',
                'https://www.cnn.com/us',
                'https://www.cnn.com/world',
                'https://www.cnn.com/politics',
                'https://www.cnn.com/money',
                'https://www.cnn.com/opinion',
                'https://www.cnn.com/health',
                'https://www.cnn.com/entertainment',
                'https://www.cnn.com/tech',
                'https://www.cnn.com/style',
                'https://www.cnn.com/travel',
                'https://www.cnn.com/sports',
                'https://www.cnn.com/video',
               ]

    def next_url(self):
        """Returns the next URL from the list, looping over the list
           as needed.
        """
        url = self.TAB_URLS[self.index];
        self.index += 1
        if self.index == len(self.TAB_URLS):
            self.index = 0
        return url


class PFMeter(threading.Thread):
    """A class whose instances measure page fault rates.  Each class instance
    (really only one is expected) runs a thread to keep track of the max rate.
    """
    def __init__(self):
        """Sets member variables."""
        threading.Thread.__init__(self)
        self.lock = threading.Lock()
        self.running = True
        self.reset()
        self.reset_max()

    def run(self):
        """Checks the average page fault rate across PF_MAX_RATE_PERIOD
           intervals, and records its maximum value.
        """
        logging.info("starting PFMeter thread")
        while self.running:
            time.sleep(PF_MAX_RATE_PERIOD)
            self.record_max()
        logging.info("PFMeter thread exiting")

    def stop(self):
        """Stops the thread."""
        self.running = False

    def reset(self):
        """Resets the meter for the purpose of computing the average rate.
           The max rate is reset with reset_max().
        """
        self.clock_start = time.time()
        self.count_start = get_pf_count()

    def reset_max(self):
        """Resets to 0 the max page fault rate seen thus far."""
        with self.lock:
            self.max_pf_rate = 0.0;
            self.max_clock_start = time.time()
            self.max_count_start = get_pf_count()

    def rate(self):
        """Returns the page fault rate (faults per second) since the last
        call to self (or to reset()).
        """
        clock_end = time.time()
        # Avoid division by zero (however unlikely) by waiting a little.
        if clock_end == self.clock_start:
            time.sleep(0.1)
            clock_end = time.time()
        delta_clock = clock_end - self.clock_start
        count_end = get_pf_count()
        delta_count = count_end - self.count_start

        rate = delta_count / delta_clock

        self.clock_start = clock_end
        self.count_start = count_end

        return rate

    def record_max(self):
        """Computes the average page fault rate since the last call to self, and
           saves the maximum values seen since the last call to reset_max().
        """
        clock = time.time()
        count = get_pf_count()
        with self.lock:
            long_interval_rate = ((count - self.max_count_start) /
                                  (clock - self.max_clock_start))
            self.max_count_start = count
            self.max_clock_start = clock
        if long_interval_rate > self.max_pf_rate:
            self.max_pf_rate = long_interval_rate
            logging.info('max pf rate = %.2f', self.max_pf_rate)

    def max_rate(self):
        """Returns the max pf rate seen since the last reset_max().
        """
        with self.lock:
            m = self.max_pf_rate
        return m

def run_realistic_memory_pressure_test(time_limit, tab_open_delay):
    """Runs a memory pressure test using live pages.
    """
    perf_results = {}
    tab_switch_delay = 0.1
    pf_meter = PFMeter()
    url_list = URLList()

    pf_meter.start()

    with chrome.Chrome() as cr:
        tabs = []
        # Phase 1 (CREATE): open tabs and cycle through them until first
        # discard.
        start_time = time.time()
        # The first tab was created at browser start.
        created_tab_count = 1
        logging.info('START OF PHASE 1')
        while True:
            for _ in range(1):
                tab = cr.browser.tabs.New()
                tabs.append(tab)
                created_tab_count += 1
                # We number tabs starting at 1.
                logging.info('navigating tab %d', created_tab_count)
                tab.Navigate(url_list.next_url());
                try:
                    url = tab.url
                    tab.action_runner.WaitForNetworkQuiescence(
                        timeout_in_seconds=30)
                except Exception as e:
                    logging.warning('network wait exception %s at %s', e, url)

            # Activate tabs to increase the working set.
            for tab in tabs:
                try:
                    tab.Activate()
                    time.sleep(tab_switch_delay)
                    # Check for tab discard.  |cr.browser.tabs| is actually a
                    # method call which returns the number of non-discarded
                    # tabs.
                    if created_tab_count > len(cr.browser.tabs):
                        break
                except:
                    logging.info('tab activation failed, assuming tab discard')
                    break;

            tab_count = len(cr.browser.tabs)
            # Check for tab discard one more time.
            if created_tab_count > tab_count:
                break

            if time.time() > time_limit:
                logging.info('test timeout')
                raise error.TestError('FAIL: phase 1 timeout with %d tabs' %
                                      created_tab_count)

        # Measure the page fault rate.
        pf_meter.reset()
        time.sleep(PF_RATE_INTERVAL)
        current_pf_rate = pf_meter.rate()
        max_pf_rate = pf_meter.max_rate()
        pf_meter.reset_max()

        discard_count = created_tab_count - tab_count
        elapsed_time = time.time() - start_time
        logging.info('opened %d tabs, %d discards',
                     created_tab_count, discard_count)
        logging.info('highest pf rate = %.2f pf/s', max_pf_rate)
        logging.info('elapsed time %.2f', elapsed_time)
        logging.info('END OF PHASE 1')
        meminfo = get_meminfo()
        perf_results['MemTotal'] = meminfo['MemTotal']
        perf_results['SwapTotal'] = meminfo['SwapTotal']

        perf_results['Phase1TabCount'] = tab_count
        perf_results['Phase1DiscardCount'] = discard_count
        perf_results['Phase1Time'] = time.time() - start_time
        perf_results['Phase1MaxPageFaultRate'] = max_pf_rate
        perf_results['Phase1PageFaultRate'] = current_pf_rate
        perf_results['Phase1MemFree'] = meminfo['MemFree']
        perf_results['Phase1SwapFree'] = meminfo['SwapFree']

        # Phase 2 (QUIESCE): Stop all activity.
        logging.info('START OF PHASE 2')
        start_time = time.time()

        # Measure the final pf rate, after one additional sleep.
        time.sleep(60)
        pf_meter.reset()
        time.sleep(PF_RATE_INTERVAL)
        current_pf_rate = pf_meter.rate()
        max_pf_rate = pf_meter.max_rate()

        pf_meter.stop()

        elapsed_time = time.time() - start_time
        browser_tab_count = len(cr.browser.tabs)
        discard_count = len(tabs) + 1 - browser_tab_count
        meminfo = get_meminfo()
        perf_results['Phase2Time'] = elapsed_time
        perf_results['Phase2TabCount'] = browser_tab_count
        perf_results['Phase2DiscardCount'] = discard_count
        perf_results['Phase2MaxPageFaultRate'] = max_pf_rate
        perf_results['Phase2PageFaultRate'] = current_pf_rate
        perf_results['Phase2MemFree'] = meminfo['MemFree']
        perf_results['Phase2SwapFree'] = meminfo['SwapFree']
        logging.info('after quiesce: discard count %d, pf rate = %.2f',
                      discard_count, current_pf_rate)
        logging.info('END OF PHASE 2')


        return perf_results

def run_simple_tab_discard_test(time_limit, tab_open_delay_seconds, bindir):
    """
    Tests that tab discarding works correctly by using a small JS program
    which uses a lot of memory.
    """
    # 1 for initial tab opened
    n_tabs = 1
    discard = False
    perf_results = {}
    start_time = time.time()
    margin = int(utils.read_file(SYSFS_MARGIN_FILENAME))

    # Open tabs until a tab discard notification arrives, or a time limit
    # is reached.
    with chrome.Chrome(init_network_controller=True) as cr:
        cr.browser.platform.SetHTTPServerDirectories(bindir)
        while True:
            tab = cr.browser.tabs.New()
            n_tabs += 1
            # The program in js-bloat.html allocates a few large arrays and
            # forces them in memory by touching some of their elements.
            tab.Navigate(cr.browser.platform.http_server.UrlOf(
                    os.path.join(bindir, 'js-bloat.html')))
            tab.WaitForDocumentReadyStateToBeComplete()
            available = int(utils.read_file(SYSFS_LOW_MEM_DIR + 'available'))
            # Slow down when getting close to the discard margin, to avoid OOM
            # kills.
            time.sleep(tab_open_delay_seconds
                       if available > 3 * margin else
                       tab_open_delay_seconds * 3)
            if n_tabs > len(cr.browser.tabs):
                logging.info('tab discard after %d tabs', n_tabs)
                break
            if time.time() > time_limit:
                raise error.TestError('FAIL: no tab discard after opening %d '
                                      'tabs in %ds' %
                                      (n_tabs, time_limit - start_time))
    perf_results["TabCountAtFirstDiscard"] = n_tabs
    return perf_results


def run_quick_tab_discard_test(time_limit, bindir):
    """
    Tests that tab discarding works correctly allocating a few tabs,
    then increasing the discard margin until a discard occurs.
    """
    # 1 for initial tab opened
    n_tabs = 1
    discard = False
    perf_results = {}
    start_time = time.time()
    original_margin = int(utils.read_file(SYSFS_MARGIN_FILENAME))

    # Open 5 tabs, so the discarder has something to work with.
    # No need to be slow here since we'll be far from OOM.
    with chrome.Chrome(init_network_controller=True) as cr:
        cr.browser.platform.SetHTTPServerDirectories(bindir)
        for _ in range(5):
            tab = cr.browser.tabs.New()
            n_tabs += 1
            # The program in js-bloat.html allocates a few large arrays and
            # forces them in memory by touching some of their elements.
            tab.Navigate(cr.browser.platform.http_server.UrlOf(
                    os.path.join(bindir, 'js-bloat.html')))
            tab.WaitForDocumentReadyStateToBeComplete()

        if n_tabs > len(cr.browser.tabs):
            raise error.TestError('unexpected early discard')

        try:
            # Increase margin to 50MB above available. Discard should happen
            # immediately, but available may change, so keep trying.
            attempt_count = 0
            while True:
                attempt_count += 1
                available = int(utils.read_file(SYSFS_AVAILABLE_FILENAME))
                logging.warning('TEMPORARILY CHANGING DISCARD MARGIN'
                                ' --- do not interrupt the test')
                utils.open_write_close(SYSFS_MARGIN_FILENAME,
                                       "%d" % (available + 50))
                # Give chrome ample time to discard tabs.
                time.sleep(2)
                if n_tabs > len(cr.browser.tabs):
                    logging.info('tab discard after %d attempts', attempt_count)
                    break
                if time.time() > time_limit:
                    raise error.TestError('FAIL: no tab discard in %d seconds' %
                                          time_limit - start_time)
        finally:
            utils.open_write_close(SYSFS_MARGIN_FILENAME, str(original_margin))
            logging.warning('discard margin was restored')

    perf_results["DiscardAttemptCount"] = attempt_count
    return perf_results


class platform_MemoryPressure(test.test):
    """Memory pressure test."""
    version = 1

    def run_once(self,
                 flavor='simple',
                 tab_open_delay_seconds=1.0,
                 timeout_seconds=1800):
        """Runs the test once.
        """
        time_limit = time.time() + timeout_seconds

        if flavor == 'simple':
            pkv = run_simple_tab_discard_test(time_limit,
                                              tab_open_delay_seconds,
                                              self.bindir)
        elif flavor == 'realistic':
            pkv = run_realistic_memory_pressure_test(time_limit,
                                                     tab_open_delay_seconds)
        elif flavor == 'quick':
            pkv = run_quick_tab_discard_test(time_limit, self.bindir)
        else:
            raise error.TestError('unexpected "flavor" parameter: %s' % flavor)

        self.write_perf_keyval(pkv)
