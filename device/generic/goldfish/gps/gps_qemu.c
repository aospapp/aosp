/*
 * Copyright (C) 2010 The Android Open Source Project
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

/* this implements a GPS hardware library for the Android emulator.
 * the following code should be built as a shared library that will be
 * placed into /system/lib/hw/gps.goldfish.so
 *
 * it will be loaded by the code in hardware/libhardware/hardware.c
 * which is itself called from android_location_GpsLocationProvider.cpp
 */


#include <errno.h>
#include <pthread.h>
#include <fcntl.h>
#include <inttypes.h>
#include <sys/epoll.h>
#include <math.h>
#include <time.h>

#define  LOG_TAG  "gps_qemu"
#include <log/log.h>
#include <cutils/sockets.h>
#include <cutils/properties.h>
#include <hardware/gps.h>
#include "qemu_pipe.h"

/* the name of the qemu-controlled pipe */
#define  QEMU_CHANNEL_NAME  "qemud:gps"

#define  GPS_DEBUG  0

#undef D
#if GPS_DEBUG
#  define  D(...)   ALOGD(__VA_ARGS__)
#else
#  define  D(...)   ((void)0)
#endif

/*****************************************************************/
/*****************************************************************/
/*****                                                       *****/
/*****       N M E A   T O K E N I Z E R                     *****/
/*****                                                       *****/
/*****************************************************************/
/*****************************************************************/

typedef struct {
    const char*  p;
    const char*  end;
} Token;

#define  MAX_NMEA_TOKENS  64

typedef struct {
    int     count;
    Token   tokens[ MAX_NMEA_TOKENS ];
} NmeaTokenizer;

/* this is the state of our connection to the qemu_gpsd daemon */
typedef struct {
    int                     init;
    int                     fd;
    GpsCallbacks            callbacks;
    pthread_t               thread;
    int                     control[2];
    pthread_mutex_t         lock;
    GpsMeasurementCallbacks*   measurement_callbacks; /* protected by lock:
                                                         accessed by main and child threads */
    bool                    gnss_enabled; /* set by ro.kernel.qemu.gps.gnss_enabled=1 */
    bool                    fix_provided_by_gnss; /* set by ro.kernel.qemu.gps.fix_by_gnss=1 */
} GpsState;

static GpsState  _gps_state[1];

static int
nmea_tokenizer_init( NmeaTokenizer*  t, const char*  p, const char*  end )
{
    int    count = 0;

    // the initial '$' is optional
    if (p < end && p[0] == '$')
        p += 1;

    // remove trailing newline
    if (end > p && end[-1] == '\n') {
        end -= 1;
        if (end > p && end[-1] == '\r')
            end -= 1;
    }

    // get rid of checksum at the end of the sentecne
    if (end >= p+3 && end[-3] == '*') {
        end -= 3;
    }

    while (p < end) {
        const char*  q = p;

        q = memchr(p, ',', end-p);
        if (q == NULL)
            q = end;

        if (count < MAX_NMEA_TOKENS) {
            t->tokens[count].p   = p;
            t->tokens[count].end = q;
            count += 1;
        }
        if (q < end)
            q += 1;

        p = q;
    }

    t->count = count;
    return count;
}

static Token
nmea_tokenizer_get( NmeaTokenizer*  t, int  index )
{
    Token  tok;
    static const char*  dummy = "";

    if (index < 0 || index >= t->count) {
        tok.p = tok.end = dummy;
    } else
        tok = t->tokens[index];

    return tok;
}


static int64_t
str2int64( const char*  p, const char*  end )
{
    int64_t   result = 0;

#if GPS_DEBUG
    char temp[1024];
    snprintf(temp, sizeof(temp), "'%.*s'", end-p, p);
#endif

    bool is_negative = false;
    if (end > p && *p == '-') {
        is_negative = true;
        ++p;
    }

    int   len    = end - p;

    for ( ; len > 0; len--, p++ )
    {
        int  c;

        if (p >= end) {
            ALOGE("parse error at func %s line %d", __func__, __LINE__);
            goto Fail;
        }

        c = *p - '0';
        if ((unsigned)c >= 10) {
            ALOGE("parse error at func %s line %d on %c", __func__, __LINE__, c);
            goto Fail;
        }

        result = result*10 + c;
    }
    if (is_negative) {
        result = - result;
    }
#if GPS_DEBUG
    ALOGD("%s ==> %" PRId64, temp, result);
#endif
    return  result;

Fail:
    return -1;
}

static int
str2int( const char*  p, const char*  end )
{
    /* danger: downward convert to 32bit */
    return str2int64(p, end);
}

static double
str2float( const char*  p, const char*  end )
{
    int   len    = end - p;
    char  temp[64];

    if (len >= (int)sizeof(temp)) {
        ALOGE("%s %d input is too long: '%.*s'", __func__, __LINE__, end-p, p);
        return 0.;
    }

    memcpy( temp, p, len );
    temp[len] = 0;
    return strtod( temp, NULL );
}

/*****************************************************************/
/*****************************************************************/
/*****                                                       *****/
/*****       N M E A   P A R S E R                           *****/
/*****                                                       *****/
/*****************************************************************/
/*****************************************************************/

#define  NMEA_MAX_SIZE  1024

typedef struct {
    int     pos;
    int     overflow;
    int     utc_year;
    int     utc_mon;
    int     utc_day;
    GpsLocation  fix;
    gps_location_callback  callback;
    GnssData    gnss_data;
    int         gnss_count;

    char    in[ NMEA_MAX_SIZE+1 ];
    bool    gnss_enabled; /* passed in from _gps_state */
    bool    fix_provided_by_gnss; /* passed in from _gps_state */
} NmeaReader;

static void
nmea_reader_init( NmeaReader*  r )
{
    memset( r, 0, sizeof(*r) );

    r->pos      = 0;
    r->overflow = 0;
    r->utc_year = -1;
    r->utc_mon  = -1;
    r->utc_day  = -1;
    r->callback = NULL;
    r->fix.size = sizeof(r->fix);

    GpsState*  s = _gps_state;
    r->gnss_enabled = s->gnss_enabled;
    r->fix_provided_by_gnss = s->fix_provided_by_gnss;

}


static int
nmea_reader_update_time( NmeaReader*  r, Token  tok )
{
    int        hour, minute;
    double     seconds;
    struct tm  tm;
    time_t     fix_time;

    if (tok.p + 6 > tok.end)
        return -1;

    if (r->utc_year < 0) {
        // no date yet, get current one
        time_t  now = time(NULL);
        gmtime_r( &now, &tm );
        r->utc_year = tm.tm_year + 1900;
        r->utc_mon  = tm.tm_mon + 1;
        r->utc_day  = tm.tm_mday;
    }

    hour    = str2int(tok.p,   tok.p+2);
    minute  = str2int(tok.p+2, tok.p+4);
    seconds = str2float(tok.p+4, tok.end);

    tm.tm_hour  = hour;
    tm.tm_min   = minute;
    tm.tm_sec   = (int) seconds;
    tm.tm_year  = r->utc_year - 1900;
    tm.tm_mon   = r->utc_mon - 1;
    tm.tm_mday  = r->utc_day;
    tm.tm_isdst = -1;

    fix_time = timegm( &tm );
    r->fix.timestamp = (long long)fix_time * 1000;
    return 0;
}

static int
nmea_reader_update_date( NmeaReader*  r, Token  date, Token  time )
{
    Token  tok = date;
    int    day, mon, year;

    if (tok.p + 6 != tok.end) {
        D("date not properly formatted: '%.*s'", tok.end-tok.p, tok.p);
        return -1;
    }
    day  = str2int(tok.p, tok.p+2);
    mon  = str2int(tok.p+2, tok.p+4);
    year = str2int(tok.p+4, tok.p+6) + 2000;

    if ((day|mon|year) < 0) {
        D("date not properly formatted: '%.*s'", tok.end-tok.p, tok.p);
        return -1;
    }

    r->utc_year  = year;
    r->utc_mon   = mon;
    r->utc_day   = day;

    return nmea_reader_update_time( r, time );
}


static double
convert_from_hhmm( Token  tok )
{
    double  val     = str2float(tok.p, tok.end);
    int     degrees = (int)(floor(val) / 100);
    double  minutes = val - degrees*100.;
    double  dcoord  = degrees + minutes / 60.0;
    return dcoord;
}


static int
nmea_reader_update_latlong( NmeaReader*  r,
                            Token        latitude,
                            char         latitudeHemi,
                            Token        longitude,
                            char         longitudeHemi )
{
    double   lat, lon;
    Token    tok;

    r->fix.flags &= ~GPS_LOCATION_HAS_LAT_LONG;

    tok = latitude;
    if (tok.p + 6 > tok.end) {
        D("latitude is too short: '%.*s'", tok.end-tok.p, tok.p);
        return -1;
    }
    lat = convert_from_hhmm(tok);
    if (latitudeHemi == 'S')
        lat = -lat;

    tok = longitude;
    if (tok.p + 6 > tok.end) {
        D("longitude is too short: '%.*s'", tok.end-tok.p, tok.p);
        return -1;
    }
    lon = convert_from_hhmm(tok);
    if (longitudeHemi == 'W')
        lon = -lon;

    r->fix.flags    |= GPS_LOCATION_HAS_LAT_LONG;
    r->fix.latitude  = lat;
    r->fix.longitude = lon;
    return 0;
}


static int
nmea_reader_update_altitude( NmeaReader* r,
                             Token altitude,
                             Token __unused units )
{
    Token   tok = altitude;

    r->fix.flags &= ~GPS_LOCATION_HAS_ALTITUDE;

    if (tok.p >= tok.end)
        return -1;

    r->fix.flags   |= GPS_LOCATION_HAS_ALTITUDE;
    r->fix.altitude = str2float(tok.p, tok.end);
    return 0;
}


static int
nmea_reader_update_bearing( NmeaReader*  r,
                            Token        bearing )
{
    Token   tok = bearing;

    r->fix.flags &= ~GPS_LOCATION_HAS_BEARING;

    if (tok.p >= tok.end)
        return -1;

    r->fix.flags   |= GPS_LOCATION_HAS_BEARING;
    r->fix.bearing  = str2float(tok.p, tok.end);
    return 0;
}


static int
nmea_reader_update_speed( NmeaReader*  r,
                          Token        speed )
{
    Token   tok = speed;

    r->fix.flags &= ~GPS_LOCATION_HAS_SPEED;

    if (tok.p >= tok.end)
        return -1;

    r->fix.flags   |= GPS_LOCATION_HAS_SPEED;
    r->fix.speed    = str2float(tok.p, tok.end);
    return 0;
}

static int
nmea_reader_update_accuracy( NmeaReader*  r )
{
    // Always return 20m accuracy.
    // Possibly parse it from the NMEA sentence in the future.
    r->fix.flags    |= GPS_LOCATION_HAS_ACCURACY;
    r->fix.accuracy = 20;
    return 0;
}

static int64_t get_int64(Token tok) {
    return str2int64(tok.p, tok.end);
}

static int get_int(Token tok) {
    return str2int(tok.p, tok.end);
}

static double get_double(Token tok) {
    return str2float(tok.p, tok.end);
}

static bool has_all_required_flags(GpsLocationFlags flags) {
    return ( flags & GPS_LOCATION_HAS_LAT_LONG
            && flags & GPS_LOCATION_HAS_ALTITUDE
           );
}

static bool is_ready_to_send(NmeaReader* r) {
    if (has_all_required_flags(r->fix.flags)) {
        if (r->gnss_enabled && r->fix_provided_by_gnss) {
            return (r->gnss_count > 2); /* required by CTS */
        }
        return true;
    }
    return false;
}

static void
nmea_reader_set_callback( NmeaReader*  r, gps_location_callback  cb )
{
    r->callback = cb;
    if (cb != NULL && is_ready_to_send(r)) {
        D("%s: sending latest fix to new callback", __FUNCTION__);
        r->callback( &r->fix );
    }
}

static void
nmea_reader_parse( NmeaReader*  r )
{
   /* we received a complete sentence, now parse it to generate
    * a new GPS fix...
    */
    NmeaTokenizer  tzer[1];
    Token          tok;

    D("Received: '%.*s'", r->pos, r->in);
    if (r->pos < 9) {
        D("Too short. discarded.");
        return;
    }

    nmea_tokenizer_init(tzer, r->in, r->in + r->pos);
#if GPS_DEBUG
    {
        int  n;
        D("Found %d tokens", tzer->count);
        for (n = 0; n < tzer->count; n++) {
            Token  tok = nmea_tokenizer_get(tzer,n);
            D("%2d: '%.*s'", n, tok.end-tok.p, tok.p);
        }
    }
#endif

    tok = nmea_tokenizer_get(tzer, 0);
    if (tok.p + 5 > tok.end) {
        D("sentence id '%.*s' too short, ignored.", tok.end-tok.p, tok.p);
        return;
    }

    // ignore first two characters.
    tok.p += 2;
    if ( !memcmp(tok.p, "GGA", 3) ) {
        // GPS fix
        Token  tok_time          = nmea_tokenizer_get(tzer,1);
        Token  tok_latitude      = nmea_tokenizer_get(tzer,2);
        Token  tok_latitudeHemi  = nmea_tokenizer_get(tzer,3);
        Token  tok_longitude     = nmea_tokenizer_get(tzer,4);
        Token  tok_longitudeHemi = nmea_tokenizer_get(tzer,5);
        Token  tok_altitude      = nmea_tokenizer_get(tzer,9);
        Token  tok_altitudeUnits = nmea_tokenizer_get(tzer,10);

        nmea_reader_update_time(r, tok_time);
        nmea_reader_update_latlong(r, tok_latitude,
                                      tok_latitudeHemi.p[0],
                                      tok_longitude,
                                      tok_longitudeHemi.p[0]);
        nmea_reader_update_altitude(r, tok_altitude, tok_altitudeUnits);

    } else if ( !memcmp(tok.p, "GNSSv1", 6) ) {
        r->gnss_data.clock.time_ns                         = get_int64(nmea_tokenizer_get(tzer,1));
        r->gnss_data.clock.full_bias_ns                    = get_int64(nmea_tokenizer_get(tzer,2));
        r->gnss_data.clock.bias_ns                         = get_double(nmea_tokenizer_get(tzer,3));
        r->gnss_data.clock.bias_uncertainty_ns             = get_double(nmea_tokenizer_get(tzer,4));
        r->gnss_data.clock.drift_nsps                      = get_double(nmea_tokenizer_get(tzer,5));
        r->gnss_data.clock.drift_uncertainty_nsps          = get_double(nmea_tokenizer_get(tzer,6));
        r->gnss_data.clock.hw_clock_discontinuity_count    = get_int(nmea_tokenizer_get(tzer,7));
        r->gnss_data.clock.flags                           = get_int(nmea_tokenizer_get(tzer,8));

        r->gnss_data.measurement_count  = get_int(nmea_tokenizer_get(tzer,9));

        for (int i = 0; i < r->gnss_data.measurement_count; ++i) {
            r->gnss_data.measurements[i].svid                               = get_int(nmea_tokenizer_get(tzer,10 + i*9 + 0));
            r->gnss_data.measurements[i].constellation                      = get_int(nmea_tokenizer_get(tzer,10 + i*9 + 1));
            r->gnss_data.measurements[i].state                              = get_int(nmea_tokenizer_get(tzer,10 + i*9 + 2));
            r->gnss_data.measurements[i].received_sv_time_in_ns             = get_int64(nmea_tokenizer_get(tzer,10 + i*9 + 3));
            r->gnss_data.measurements[i].received_sv_time_uncertainty_in_ns = get_int64(nmea_tokenizer_get(tzer,10 + i*9 + 4));
            r->gnss_data.measurements[i].c_n0_dbhz                          = get_double(nmea_tokenizer_get(tzer,10 + i*9 + 5));
            r->gnss_data.measurements[i].pseudorange_rate_mps               = get_double(nmea_tokenizer_get(tzer,10 + i*9 + 6));
            r->gnss_data.measurements[i].pseudorange_rate_uncertainty_mps   = get_double(nmea_tokenizer_get(tzer,10 + i*9 + 7));
            r->gnss_data.measurements[i].carrier_frequency_hz               = get_double(nmea_tokenizer_get(tzer,10 + i*9 + 8));
            r->gnss_data.measurements[i].flags                              = GNSS_MEASUREMENT_HAS_CARRIER_FREQUENCY;
        }
    } else if ( !memcmp(tok.p, "GSA", 3) ) {
        // do something ?
    } else if ( !memcmp(tok.p, "RMC", 3) ) {
        Token  tok_time          = nmea_tokenizer_get(tzer,1);
        Token  tok_fixStatus     = nmea_tokenizer_get(tzer,2);
        Token  tok_latitude      = nmea_tokenizer_get(tzer,3);
        Token  tok_latitudeHemi  = nmea_tokenizer_get(tzer,4);
        Token  tok_longitude     = nmea_tokenizer_get(tzer,5);
        Token  tok_longitudeHemi = nmea_tokenizer_get(tzer,6);
        Token  tok_speed         = nmea_tokenizer_get(tzer,7);
        Token  tok_bearing       = nmea_tokenizer_get(tzer,8);
        Token  tok_date          = nmea_tokenizer_get(tzer,9);

        D("in RMC, fixStatus=%c", tok_fixStatus.p[0]);
        if (tok_fixStatus.p[0] == 'A')
        {
            nmea_reader_update_date( r, tok_date, tok_time );

            nmea_reader_update_latlong( r, tok_latitude,
                                           tok_latitudeHemi.p[0],
                                           tok_longitude,
                                           tok_longitudeHemi.p[0] );

            nmea_reader_update_bearing( r, tok_bearing );
            nmea_reader_update_speed  ( r, tok_speed );
        }
    } else {
        tok.p -= 2;
        D("unknown sentence '%.*s", tok.end-tok.p, tok.p);
    }

    // Always update accuracy
    nmea_reader_update_accuracy( r );

    if (is_ready_to_send(r)) {
#if GPS_DEBUG
        char   temp[256];
        char*  p   = temp;
        char*  end = p + sizeof(temp);
        struct tm   utc;

        p += snprintf( p, end-p, "sending fix" );
        if (r->fix.flags & GPS_LOCATION_HAS_LAT_LONG) {
            p += snprintf(p, end-p, " lat=%g lon=%g", r->fix.latitude, r->fix.longitude);
        }
        if (r->fix.flags & GPS_LOCATION_HAS_ALTITUDE) {
            p += snprintf(p, end-p, " altitude=%g", r->fix.altitude);
        }
        if (r->fix.flags & GPS_LOCATION_HAS_SPEED) {
            p += snprintf(p, end-p, " speed=%g", r->fix.speed);
        }
        if (r->fix.flags & GPS_LOCATION_HAS_BEARING) {
            p += snprintf(p, end-p, " bearing=%g", r->fix.bearing);
        }
        if (r->fix.flags & GPS_LOCATION_HAS_ACCURACY) {
            p += snprintf(p,end-p, " accuracy=%g", r->fix.accuracy);
        }
        //The unit of r->fix.timestamp is millisecond.
        time_t timestamp = r->fix.timestamp / 1000;
        gmtime_r( (time_t*) &timestamp, &utc );
        p += snprintf(p, end-p, " time=%s", asctime( &utc ) );
#endif
        if (r->callback) {
            D("%s", temp);
            r->callback( &r->fix );
            /* we have sent a complete fix, now prepare for next complete fix */
            r->fix.flags = 0;
        }
        else {
            D("no callback, keeping data until needed !");
        }
    }

    if (r->gnss_data.measurement_count > 0) {
        /* this runs in child thread */
        GpsState*  s = _gps_state;
        pthread_mutex_lock(&s->lock);
        if (s->measurement_callbacks && s->measurement_callbacks->gnss_measurement_callback) {
            D("sending gnss measurement data");
            s->measurement_callbacks->gnss_measurement_callback(&r->gnss_data);
            r->gnss_data.measurement_count = 0;
            r->gnss_count ++;
        } else {
            D("no gnss measurement_callbacks, keeping data until needed !");
        }
        pthread_mutex_unlock(&s->lock);
    }
}


static void
nmea_reader_addc( NmeaReader*  r, int  c )
{
    if (r->overflow) {
        r->overflow = (c != '\n');
        return;
    }

    if (r->pos >= (int) sizeof(r->in)-1 ) {
        r->overflow = 1;
        r->pos      = 0;
        return;
    }

    r->in[r->pos] = (char)c;
    r->pos       += 1;

    if (c == '\n') {
        nmea_reader_parse( r );
        r->pos = 0;
    }
}


/*****************************************************************/
/*****************************************************************/
/*****                                                       *****/
/*****       C O N N E C T I O N   S T A T E                 *****/
/*****                                                       *****/
/*****************************************************************/
/*****************************************************************/

/* commands sent to the gps thread */
enum {
    CMD_QUIT  = 0,
    CMD_START = 1,
    CMD_STOP  = 2
};



static void
gps_state_done( GpsState*  s )
{
    // tell the thread to quit, and wait for it
    char   cmd = CMD_QUIT;
    void*  dummy;
    write( s->control[0], &cmd, 1 );
    pthread_join(s->thread, &dummy);

    pthread_mutex_destroy(&s->lock);

    // close the control socket pair
    close( s->control[0] ); s->control[0] = -1;
    close( s->control[1] ); s->control[1] = -1;

    // close connection to the QEMU GPS daemon
    close( s->fd ); s->fd = -1;
    s->init = 0;
}


static void
gps_state_start( GpsState*  s )
{
    char  cmd = CMD_START;
    int   ret;

    do { ret=write( s->control[0], &cmd, 1 ); }
    while (ret < 0 && errno == EINTR);

    if (ret != 1)
        D("%s: could not send CMD_START command: ret=%d: %s",
          __FUNCTION__, ret, strerror(errno));
}


static void
gps_state_stop( GpsState*  s )
{
    char  cmd = CMD_STOP;
    int   ret;

    do { ret=write( s->control[0], &cmd, 1 ); }
    while (ret < 0 && errno == EINTR);

    if (ret != 1)
        D("%s: could not send CMD_STOP command: ret=%d: %s",
          __FUNCTION__, ret, strerror(errno));
}


static int
epoll_register( int  epoll_fd, int  fd )
{
    struct epoll_event  ev;
    int                 ret, flags;

    /* important: make the fd non-blocking */
    flags = fcntl(fd, F_GETFL);
    fcntl(fd, F_SETFL, flags | O_NONBLOCK);

    ev.events  = EPOLLIN;
    ev.data.fd = fd;
    do {
        ret = epoll_ctl( epoll_fd, EPOLL_CTL_ADD, fd, &ev );
    } while (ret < 0 && errno == EINTR);
    return ret;
}


// static int
// epoll_deregister( int  epoll_fd, int  fd )
// {
//     int  ret;
//     do {
//         ret = epoll_ctl( epoll_fd, EPOLL_CTL_DEL, fd, NULL );
//     } while (ret < 0 && errno == EINTR);
//     return ret;
// }

/* this is the main thread, it waits for commands from gps_state_start/stop and,
 * when started, messages from the QEMU GPS daemon. these are simple NMEA sentences
 * that must be parsed to be converted into GPS fixes sent to the framework
 */
static void
gps_state_thread( void*  arg )
{
    GpsState*   state = (GpsState*) arg;
    NmeaReader  reader[1];
    int         epoll_fd   = epoll_create(2);
    int         started    = 0;
    int         gps_fd     = state->fd;
    int         control_fd = state->control[1];
    GpsStatus gps_status;
    gps_status.size = sizeof(gps_status);
    GpsSvStatus  gps_sv_status;
    memset(&gps_sv_status, 0, sizeof(gps_sv_status));
    gps_sv_status.size = sizeof(gps_sv_status);
    gps_sv_status.num_svs = 1;
    gps_sv_status.sv_list[0].size = sizeof(gps_sv_status.sv_list[0]);
    gps_sv_status.sv_list[0].prn = 17;
    gps_sv_status.sv_list[0].snr = 60.0;
    gps_sv_status.sv_list[0].elevation = 30.0;
    gps_sv_status.sv_list[0].azimuth = 30.0;

    nmea_reader_init( reader );

    // register control file descriptors for polling
    epoll_register( epoll_fd, control_fd );
    epoll_register( epoll_fd, gps_fd );

    D("gps thread running");

    // now loop
    for (;;) {
        struct epoll_event   events[2];
        int                  ne, nevents;

        int timeout = -1;
        if (gps_status.status == GPS_STATUS_SESSION_BEGIN) {
            timeout = 10 * 1000; // 10 seconds
        }
        nevents = epoll_wait( epoll_fd, events, 2, timeout );
        if (state->callbacks.sv_status_cb) {
            state->callbacks.sv_status_cb(&gps_sv_status);
        }
        // update satilite info
        if (nevents < 0) {
            if (errno != EINTR)
                ALOGE("epoll_wait() unexpected error: %s", strerror(errno));
            continue;
        }
        D("gps thread received %d events", nevents);
        for (ne = 0; ne < nevents; ne++) {
            if ((events[ne].events & (EPOLLERR|EPOLLHUP)) != 0) {
                ALOGE("EPOLLERR or EPOLLHUP after epoll_wait() !?");
                return;
            }
            if ((events[ne].events & EPOLLIN) != 0) {
                int  fd = events[ne].data.fd;

                if (fd == control_fd)
                {
                    char  cmd = 0xFF;
                    int   ret;
                    D("gps control fd event");
                    do {
                        ret = read( fd, &cmd, 1 );
                    } while (ret < 0 && errno == EINTR);

                    if (cmd == CMD_QUIT) {
                        D("gps thread quitting on demand");
                        return;
                    }
                    else if (cmd == CMD_START) {
                        if (!started) {
                            D("gps thread starting  location_cb=%p", state->callbacks.location_cb);
                            started = 1;
                            reader->gnss_count = 0;
                            nmea_reader_set_callback( reader, state->callbacks.location_cb );
                            gps_status.status = GPS_STATUS_SESSION_BEGIN;
                            if (state->callbacks.status_cb) {
                                state->callbacks.status_cb(&gps_status);
                            }
                        }
                    }
                    else if (cmd == CMD_STOP) {
                        if (started) {
                            D("gps thread stopping");
                            started = 0;
                            nmea_reader_set_callback( reader, NULL );
                            gps_status.status = GPS_STATUS_SESSION_END;
                            if (state->callbacks.status_cb) {
                                state->callbacks.status_cb(&gps_status);
                            }
                        }
                    }
                }
                else if (fd == gps_fd)
                {
                    char  buff[32];
                    D("gps fd event");
                    for (;;) {
                        int  nn, ret;

                        ret = read( fd, buff, sizeof(buff) );
                        if (ret < 0) {
                            if (errno == EINTR)
                                continue;
                            if (errno != EWOULDBLOCK)
                                ALOGE("error while reading from gps daemon socket: %s:", strerror(errno));
                            break;
                        }
                        D("received %d bytes: %.*s", ret, ret, buff);
                        for (nn = 0; nn < ret; nn++)
                            nmea_reader_addc( reader, buff[nn] );
                    }
                    D("gps fd event end");
                }
                else
                {
                    ALOGE("epoll_wait() returned unkown fd %d ?", fd);
                }
            }
        }
    }
}

#define  BUFF_SIZE   (PROPERTY_KEY_MAX + PROPERTY_VALUE_MAX + 2)
static bool is_gnss_measurement_enabled() {
    char temp[BUFF_SIZE];
    property_get("ro.kernel.qemu.gps.gnss_enabled", temp, "");
    return (strncmp(temp, "1", 1) == 0);
}

static bool is_fix_provided_by_gnss_measurement() {
    char temp[BUFF_SIZE];
    property_get("ro.kernel.qemu.gps.fix_by_gnss", temp, "");
    return (strncmp(temp, "1", 1) == 0);
}

static void
gps_state_init( GpsState*  state, GpsCallbacks* callbacks )
{
    state->init       = 1;
    state->control[0] = -1;
    state->control[1] = -1;
    state->fd         = -1;

    state->fd = qemu_pipe_open(QEMU_CHANNEL_NAME);

    if (state->fd < 0) {
        D("no gps emulation detected");
        return;
    }

    D("gps emulation will read from '%s' qemu pipe", QEMU_CHANNEL_NAME );

    if ( socketpair( AF_LOCAL, SOCK_STREAM, 0, state->control ) < 0 ) {
        ALOGE("could not create thread control socket pair: %s", strerror(errno));
        goto Fail;
    }

    state->gnss_enabled = is_gnss_measurement_enabled();
    D("gnss_enabled:%s", state->gnss_enabled ? "yes":"no");
    state->fix_provided_by_gnss = is_fix_provided_by_gnss_measurement();

    pthread_mutex_init (&state->lock, (const pthread_mutexattr_t *) NULL);

    state->thread = callbacks->create_thread_cb( "gps_state_thread", gps_state_thread, state );

    if ( !state->thread ) {
        ALOGE("could not create gps thread: %s", strerror(errno));
        goto Fail;
    }

    state->callbacks = *callbacks;

    // Explicitly initialize capabilities
    state->callbacks.set_capabilities_cb(0);




    // Setup system info, we are pre 2016 hardware.
    GnssSystemInfo sysinfo;
    sysinfo.size = sizeof(GnssSystemInfo);
    sysinfo.year_of_hw = 2015;
    state->callbacks.set_system_info_cb(&sysinfo);
    if (state->gnss_enabled) {
        D("enabling GPS_CAPABILITY_MEASUREMENTS");
        state->callbacks.set_capabilities_cb(GPS_CAPABILITY_MEASUREMENTS);
    }

    D("gps state initialized");
    return;

Fail:
    gps_state_done( state );
}


/*****************************************************************/
/*****************************************************************/
/*****                                                       *****/
/*****       I N T E R F A C E                               *****/
/*****                                                       *****/
/*****************************************************************/
/*****************************************************************/


static int
qemu_gps_init(GpsCallbacks* callbacks)
{
    GpsState*  s = _gps_state;

    if (!s->init)
        gps_state_init(s, callbacks);

    if (s->fd < 0)
        return -1;

    return 0;
}

static void
qemu_gps_cleanup(void)
{
    GpsState*  s = _gps_state;

    if (s->init)
        gps_state_done(s);
}


static int
qemu_gps_start()
{
    GpsState*  s = _gps_state;

    if (!s->init) {
        D("%s: called with uninitialized state !!", __FUNCTION__);
        return -1;
    }

    D("%s: called", __FUNCTION__);
    gps_state_start(s);
    return 0;
}


static int
qemu_gps_stop()
{
    GpsState*  s = _gps_state;

    if (!s->init) {
        D("%s: called with uninitialized state !!", __FUNCTION__);
        return -1;
    }

    D("%s: called", __FUNCTION__);
    gps_state_stop(s);
    return 0;
}


static int
qemu_gps_inject_time(GpsUtcTime __unused time,
                     int64_t __unused timeReference,
                     int __unused uncertainty)
{
    return 0;
}

static int
qemu_gps_inject_location(double __unused latitude,
                         double __unused longitude,
                         float __unused accuracy)
{
    return 0;
}

static void
qemu_gps_delete_aiding_data(GpsAidingData __unused flags)
{
}

static int qemu_gps_set_position_mode(GpsPositionMode __unused mode,
                                      GpsPositionRecurrence __unused recurrence,
                                      uint32_t __unused min_interval,
                                      uint32_t __unused preferred_accuracy,
                                      uint32_t __unused preferred_time)
{
    // FIXME - support fix_frequency
    return 0;
}

static int qemu_gps_measurement_init(GpsMeasurementCallbacks* callbacks) {
    /* this runs in main thread */
    D("calling %s with input %p", __func__, callbacks);
    GpsState*  s = _gps_state;
    pthread_mutex_lock(&s->lock);
    s->measurement_callbacks = callbacks;
    pthread_mutex_unlock(&s->lock);

    return 0;
}

static void qemu_gps_measurement_close() {
    /* this runs in main thread */
    D("calling %s", __func__);
    GpsState*  s = _gps_state;
    pthread_mutex_lock(&s->lock);
    s->measurement_callbacks = NULL;
    pthread_mutex_unlock(&s->lock);
}

static const GpsMeasurementInterface qemuGpsMeasurementInterface  = {
    sizeof(GpsMeasurementInterface),
    qemu_gps_measurement_init,
    qemu_gps_measurement_close,
};

static const void*
qemu_gps_get_extension(const char* name)
{
    if(name && strcmp(name, GPS_MEASUREMENT_INTERFACE) == 0) {
        /* when this is called, _gps_state is not initialized yet */
        bool gnss_enabled = is_gnss_measurement_enabled();
        if (gnss_enabled) {
            D("calling %s with GPS_MEASUREMENT_INTERFACE enabled", __func__);
            return &qemuGpsMeasurementInterface;
        }
    }
    return NULL;
}

static const GpsInterface  qemuGpsInterface = {
    sizeof(GpsInterface),
    qemu_gps_init,
    qemu_gps_start,
    qemu_gps_stop,
    qemu_gps_cleanup,
    qemu_gps_inject_time,
    qemu_gps_inject_location,
    qemu_gps_delete_aiding_data,
    qemu_gps_set_position_mode,
    qemu_gps_get_extension,
};

const GpsInterface* gps__get_gps_interface(struct gps_device_t* __unused dev)
{
    return &qemuGpsInterface;
}

static int open_gps(const struct hw_module_t* module,
                    char const* __unused name,
                    struct hw_device_t** device)
{
    struct gps_device_t *dev = malloc(sizeof(struct gps_device_t));
    memset(dev, 0, sizeof(*dev));

    dev->common.tag = HARDWARE_DEVICE_TAG;
    dev->common.version = 0;
    dev->common.module = (struct hw_module_t*)module;
//    dev->common.close = (int (*)(struct hw_device_t*))close_lights;
    dev->get_gps_interface = gps__get_gps_interface;

    *device = (struct hw_device_t*)dev;
    return 0;
}


static struct hw_module_methods_t gps_module_methods = {
    .open = open_gps
};

struct hw_module_t HAL_MODULE_INFO_SYM = {
    .tag = HARDWARE_MODULE_TAG,
    .version_major = 1,
    .version_minor = 0,
    .id = GPS_HARDWARE_MODULE_ID,
    .name = "Goldfish GPS Module",
    .author = "The Android Open Source Project",
    .methods = &gps_module_methods,
};
