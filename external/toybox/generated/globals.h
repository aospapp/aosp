// toys/android/getprop.c

struct getprop_data {
  size_t size;
  char **nv; // name/value pairs: even=name, odd=value
  struct selabel_handle *handle;
};

// toys/android/log.c

struct log_data {
  char *t, *p;
};

// toys/example/hello.c

struct hello_data {
  int unused;
};

// toys/example/skeleton.c

struct skeleton_data {
  union {
    struct {
      char *b;
      long c;
      struct arg_list *d;
      long e;
      char *also, *blubber;
    } s;
    struct {
      long b;
    } a;
  };

  int more_globals;
};

// toys/lsb/dmesg.c

struct dmesg_data {
  long n, s;

  int use_color;
  time_t tea;
};

// toys/lsb/hostname.c

struct hostname_data {
  char *F;
};

// toys/lsb/killall.c

struct killall_data {
  char *s;

  int signum;
  pid_t cur_pid;
  char **names;
  short *err;
};

// toys/lsb/md5sum.c

struct md5sum_data {
  int sawline;

  // Crypto variables blanked after summing
  unsigned state[5];
  unsigned oldstate[5];
  uint64_t count;
  union {
    char c[64];
    unsigned i[16];
  } buffer;
};

// toys/lsb/mknod.c

struct mknod_data {
  char *Z, *m;
};

// toys/lsb/mktemp.c

struct mktemp_data {
  char *p;
};

// toys/lsb/mount.c

struct mount_data {
  struct arg_list *optlist;
  char *type;
  char *bigO;

  unsigned long flags;
  char *opts;
  int okuser;
};

// toys/lsb/passwd.c

struct passwd_data {
  char *a;
};

// toys/lsb/pidof.c

struct pidof_data {
  char *omit;
};

// toys/lsb/seq.c

struct seq_data {
  char *s, *f;

  int precision;
};

// toys/lsb/su.c

struct su_data {
  char *s;
  char *c;
};

// toys/lsb/umount.c

struct umount_data {
  struct arg_list *t;

  char *types;
};

// toys/net/ftpget.c

struct ftpget_data {
  char *u, *p, *P;

  int fd;
};

// toys/net/ifconfig.c

struct ifconfig_data {
  int sockfd;
};

// toys/net/microcom.c

struct microcom_data {
  char *s;

  int fd;
  struct termios original_stdin_state, original_fd_state;
};

// toys/net/netcat.c

struct netcat_data {
  char *f, *s;
  long q, p, W, w;
};

// toys/net/netstat.c

struct netstat_data {
  struct num_cache *inodes;
  int wpad;
};;

// toys/net/ping.c

struct ping_data {
  char *I;
  long w, W, i, s, c, t, m;

  struct sockaddr *sa;
  int sock;
  unsigned long sent, recv, fugit, min, max;
};

// toys/net/sntp.c

struct sntp_data {
  long r;
  char *p, *m, *M;
};

// toys/net/tunctl.c

struct tunctl_data {
  char *u;
};

// toys/other/acpi.c

struct acpi_data {
  int ac, bat, therm, cool;
  char *cpath;
};

// toys/other/base64.c

struct base64_data {
  long w;

  unsigned total;
};

// toys/other/blockdev.c

struct blockdev_data {
  long setbsz, setra;
};

// toys/other/chrt.c

struct chrt_data {
  long p;
};

// toys/other/dos2unix.c

struct dos2unix_data {
  char *tempfile;
};

// toys/other/fallocate.c

struct fallocate_data {
  long offset;
  long size;
};

// toys/other/fmt.c

struct fmt_data {
  int width;

  int level, pos;
};

// toys/other/free.c

struct free_data {
  unsigned bits;
  unsigned long long units;
  char *buf;
};

// toys/other/hexedit.c

struct hexedit_data {
  char *data;
  long long len, base;
  int numlen, undo, undolen;
  unsigned height;
};

// toys/other/hwclock.c

struct hwclock_data {
  char *f;

  int utc;
};

// toys/other/ionice.c

struct ionice_data {
  long pid;
  long level;
  long class;
};

// toys/other/login.c

struct login_data {
  char *h, *f;

  int login_timeout, login_fail_timeout;
};

// toys/other/losetup.c

struct losetup_data {
  char *j;
  long o, S;

  int openflags;
  dev_t jdev;
  ino_t jino;
};

// toys/other/lspci.c

struct lspci_data {
  char *i;
  long n;

  FILE *db;
};

// toys/other/makedevs.c

struct makedevs_data {
  char *fname;
};

// toys/other/mix.c

struct mix_data {
   long r, l;
   char *d, *c;
};

// toys/other/mkpasswd.c

struct mkpasswd_data {
  long P;
  char *m, *S;
};

// toys/other/mkswap.c

struct mkswap_data {
  char *L;
};

// toys/other/modinfo.c

struct modinfo_data {
  char *F, *k, *b;

  long mod;
};

// toys/other/nsenter.c

struct nsenter_data {
  char *Uupnmi[6];
  long t;
};

// toys/other/oneit.c

struct oneit_data {
  char *c;
};

// toys/other/setfattr.c

struct setfattr_data {
  char *x, *v, *n;
};

// toys/other/shred.c

struct shred_data {
  long o, n, s;
};

// toys/other/stat.c

struct stat_data {
  char *c;

  union {
    struct stat st;
    struct statfs sf;
  } stat;
  char *file, *pattern;
  int patlen;
};

// toys/other/swapon.c

struct swapon_data {
  long p;
};

// toys/other/switch_root.c

struct switch_root_data {
  char *c;

  dev_t rootdev;
};

// toys/other/timeout.c

struct timeout_data {
  char *s, *k;

  int nextsig;
  pid_t pid;
  struct timeval ktv;
  struct itimerval itv;
};

// toys/other/truncate.c

struct truncate_data {
  char *s;

  long size;
  int type;
};

// toys/other/watch.c

struct watch_data {
  int n;

  pid_t pid, oldpid;
};

// toys/other/xxd.c

struct xxd_data {
  long s, g, o, l, c;
};

// toys/pending/arp.c

struct arp_data {
    char *hw_type;
    char *af_type_A;
    char *af_type_p;
    char *interface;
    
    int sockfd;
    char *device;
};

// toys/pending/arping.c

struct arping_data {
    long count;
    unsigned long time_out;
    char *iface;
    char *src_ip;

    int sockfd;
    unsigned long start, end;
    unsigned sent_at, sent_nr, rcvd_nr, brd_sent, rcvd_req, brd_rcv,
             unicast_flag;
};

// toys/pending/bc.c

struct bc_data {
  // This actually needs to be a BcVm*, but the toybox build
  // system complains if I make it so. Instead, we'll just cast.
  char *vm;

  size_t nchars;
  char *file, sig, max_ibase;
  uint16_t line_len;
};

// toys/pending/bootchartd.c

struct bootchartd_data {
  char buf[32];
  long smpl_period_usec;
  int proc_accounting;
  int is_login;

  void *head;
};

// toys/pending/brctl.c

struct brctl_data {
    int sockfd;
};

// toys/pending/crond.c

struct crond_data {
  char *crontabs_dir;
  char *logfile;
  int loglevel_d;
  int loglevel;

  time_t crontabs_dir_mtime;
  uint8_t flagd;
};

// toys/pending/crontab.c

struct crontab_data {
  char *user;
  char *cdir;
};

// toys/pending/dd.c

struct dd_data {
  int show_xfer, show_records;
  unsigned long long bytes, c_count, in_full, in_part, out_full, out_part;
  struct timeval start;
  struct {
    char *name;
    int fd;
    unsigned char *buff, *bp;
    long sz, count;
    unsigned long long offset;
  } in, out;
};;

// toys/pending/dhcp.c

struct dhcp_data {
    char *iface;
    char *pidfile;
    char *script;
    long retries;
    long timeout;
    long tryagain;
    struct arg_list *req_opt;
    char *req_ip;
    struct arg_list *pkt_opt;
    char *fdn_name;
    char *hostname;
    char *vendor_cls;
};

// toys/pending/dhcp6.c

struct dhcp6_data {
  char *interface_name, *pidfile, *script;
  long retry, timeout, errortimeout;
  char *req_ip;
  int length, state, request_length, sock, sock1, status, retval, retries;
  struct timeval tv;
  uint8_t transction_id[3];
  struct sockaddr_in6 input_socket6;
};

// toys/pending/dhcpd.c

struct dhcpd_data {
    char *iface;
    long port;
};;

// toys/pending/diff.c

struct diff_data {
  long ct;
  char *start;
  struct arg_list *L_list;

  int dir_num, size, is_binary, status, change, len[2];
  int *offset[2];
  struct stat st[2];
};

// toys/pending/dumpleases.c

struct dumpleases_data {
    char *file;
};

// toys/pending/expr.c

struct expr_data {
  char **tok; // current token, not on the stack since recursive calls mutate it

  char *refree;
};

// toys/pending/fdisk.c

struct fdisk_data {
  long sect_sz;
  long sectors;
  long heads;
  long cylinders;
};

// toys/pending/fold.c

struct fold_data {
  int width;
};

// toys/pending/fsck.c

struct fsck_data {
  int fd_num;
  char *t_list;

  struct double_list *devices;
  char *arr_flag;
  char **arr_type;
  int negate;
  int sum_status;
  int nr_run;
  int sig_num;
  long max_nr_run;
};

// toys/pending/getfattr.c

struct getfattr_data {
  char *n;
};

// toys/pending/getty.c

struct getty_data {
  char *issue_str;
  char *login_str;
  char *init_str;
  char *host_str; 
  long timeout;
  
  char *tty_name;  
  int  speeds[20];
  int  sc;              
  struct termios termios;
  char buff[128];
};

// toys/pending/groupadd.c

struct groupadd_data {
  long gid;
};

// toys/pending/gzip.c

struct gzip_data {
  int level;
};

// toys/pending/host.c

struct host_data {
  char *type_str;
};

// toys/pending/ip.c

struct ip_data {
  char stats, singleline, flush, *filter_dev, gbuf[8192];
  int sockfd, connected, from_ok, route_cmd;
  int8_t addressfamily, is_addr;
};

// toys/pending/ipcrm.c

struct ipcrm_data {
  struct arg_list *qkey;
  struct arg_list *qid;
  struct arg_list *skey;
  struct arg_list *sid;
  struct arg_list *mkey;
  struct arg_list *mid;
};

// toys/pending/ipcs.c

struct ipcs_data {
  int id;
};

// toys/pending/klogd.c

struct klogd_data {
  long level;

  int fd;
};

// toys/pending/last.c

struct last_data {
  char *file;

  struct arg_list *list;
};

// toys/pending/lsof.c

struct lsof_data {
  struct arg_list *p;

  struct stat *sought_files;
  struct double_list *all_sockets, *files;
  int last_shown_pid, shown_header;
};

// toys/pending/mke2fs.c

struct mke2fs_data {
  // Command line arguments.
  long blocksize;
  long bytes_per_inode;
  long inodes;           // Total inodes in filesystem.
  long reserved_percent; // Integer precent of space to reserve for root.
  char *gendir;          // Where to read dirtree from.

  // Internal data.
  struct dirtree *dt;    // Tree of files to copy into the new filesystem.
  unsigned treeblocks;   // Blocks used by dt
  unsigned treeinodes;   // Inodes used by dt

  unsigned blocks;       // Total blocks in the filesystem.
  unsigned freeblocks;   // Free blocks in the filesystem.
  unsigned inodespg;     // Inodes per group
  unsigned groups;       // Total number of block groups.
  unsigned blockbits;    // Bits per block.  (Also blocks per group.)

  // For gene2fs
  unsigned nextblock;    // Next data block to allocate
  unsigned nextgroup;    // Next group we'll be allocating from
  int fsfd;              // File descriptor of filesystem (to output to).
};

// toys/pending/modprobe.c

struct modprobe_data {
  struct arg_list *dirs;

  struct arg_list *probes;
  struct arg_list *dbase[256];
  char *cmdopts;
  int nudeps;
  uint8_t symreq;
};

// toys/pending/more.c

struct more_data {
  struct termios inf;
  int cin_fd;
};

// toys/pending/openvt.c

struct openvt_data {
  unsigned long vt_num;
};

// toys/pending/route.c

struct route_data {
  char *family;
};

// toys/pending/sh.c

struct sh_data {
  char *command;

  long lineno;
};

// toys/pending/stty.c

struct stty_data {
  char *device;

  int fd, col;
  unsigned output_cols;
};

// toys/pending/sulogin.c

struct sulogin_data {
  long timeout;
  struct termios crntio;
};

// toys/pending/syslogd.c

struct syslogd_data {
  char *socket;
  char *config_file;
  char *unix_socket;
  char *logfile;
  long interval;
  long rot_size;
  long rot_count;
  char *remote_log;
  long log_prio;

  struct unsocks *lsocks;  // list of listen sockets
  struct logfile *lfiles;  // list of write logfiles
  int sigfd[2];
};

// toys/pending/tar.c

struct tar_data {
  char *fname;
  char *dir;
  struct arg_list *inc_file;
  struct arg_list *exc_file;
  char *tocmd;
  struct arg_list *exc;

  struct arg_list *inc, *pass;
  void *inodes, *handle;
};

// toys/pending/tcpsvd.c

struct tcpsvd_data {
  char *name;
  char *user;
  long bn;
  char *nmsg;
  long cn;

  int maxc;
  int count_all;
  int udp;
};

// toys/pending/telnet.c

struct telnet_data {
  int port;
  int sfd;
  char buff[128];
  int pbuff;
  char iac[256];
  int piac;
  char *ttype;
  struct termios def_term;
  struct termios raw_term;
  uint8_t term_ok;
  uint8_t term_mode;
  uint8_t flags;
  unsigned win_width;
  unsigned win_height;
};

// toys/pending/telnetd.c

struct telnetd_data {
    char *login_path;
    char *issue_path;
    int port;
    char *host_addr;
    long w_sec;

    int gmax_fd;
    pid_t fork_pid;
};

// toys/pending/tftp.c

struct tftp_data {
  char *local_file;
  char *remote_file;
  long block_size;

  struct sockaddr_storage inaddr;
  int af;
};

// toys/pending/tftpd.c

struct tftpd_data {
  char *user;

  long sfd;
  struct passwd *pw;
};

// toys/pending/tr.c

struct tr_data {
  short map[256]; //map of chars
  int len1, len2;
};

// toys/pending/traceroute.c

struct traceroute_data {
  long max_ttl;
  long port;
  long ttl_probes;
  char *src_ip;
  long tos;
  long wait_time;
  struct arg_list *loose_source;
  long pause_time;
  long first_ttl;
  char *iface;

  uint32_t gw_list[9];
  int recv_sock;
  int snd_sock;
  unsigned msg_len;
  char *packet;
  uint32_t ident;
  int istraceroute6;
};

// toys/pending/useradd.c

struct useradd_data {
  char *dir;
  char *gecos;
  char *shell;
  char *u_grp;
  long uid;

  long gid;
};

// toys/pending/vi.c

struct vi_data {
    int cur_col;
    int cur_row;
    unsigned screen_height;
    unsigned screen_width;
    int vi_mode;
};

// toys/pending/wget.c

struct wget_data {
  char *filename;
};

// toys/posix/basename.c

struct basename_data {
  char *s;
};

// toys/posix/chgrp.c

struct chgrp_data {
  uid_t owner;
  gid_t group;
  char *owner_name, *group_name;
  int symfollow;
};

// toys/posix/chmod.c

struct chmod_data {
  char *mode;
};

// toys/posix/cksum.c

struct cksum_data {
  unsigned crc_table[256];
};

// toys/posix/cmp.c

struct cmp_data {
  int fd;
  char *name;
};

// toys/posix/cp.c

struct cp_data {
  union {
    // install's options
    struct {
      char *g, *o, *m;
    } i;
    // cp's options
    struct {
      char *preserve;
    } c;
  };

  char *destname;
  struct stat top;
  int (*callback)(struct dirtree *try);
  uid_t uid;
  gid_t gid;
  int pflags;
};

// toys/posix/cpio.c

struct cpio_data {
  char *F, *p, *H;
};

// toys/posix/cut.c

struct cut_data {
  char *d, *O;
  struct arg_list *select[5]; // we treat them the same, so loop through

  int pairs;
  regex_t reg;
};

// toys/posix/date.c

struct date_data {
  char *r, *D, *d;

  unsigned nano;
};

// toys/posix/df.c

struct df_data {
  struct arg_list *t;

  long units;
  int column_widths[5];
  int header_shown;
};

// toys/posix/du.c

struct du_data {
  long d;

  unsigned long depth, total;
  dev_t st_dev;
  void *inodes;
};

// toys/posix/env.c

struct env_data {
  struct arg_list *u;
};;

// toys/posix/expand.c

struct expand_data {
  struct arg_list *t;

  unsigned tabcount, *tab;
};

// toys/posix/file.c

struct file_data {
  int max_name_len;

  off_t len;
};

// toys/posix/find.c

struct find_data {
  char **filter;
  struct double_list *argdata;
  int topdir, xdev, depth;
  time_t now;
  long max_bytes;
};

// toys/posix/grep.c

struct grep_data {
  long m, A, B, C;
  struct arg_list *f, *e, *M, *S;
  char *color;

  char *purple, *cyan, *red, *green, *grey;
  struct double_list *reg;
  char indelim, outdelim;
  int found, tried;
};

// toys/posix/head.c

struct head_data {
  long c, n;

  int file_no;
};

// toys/posix/iconv.c

struct iconv_data {
  char *f, *t;

  void *ic;
};

// toys/posix/id.c

struct id_data {
  int is_groups;
};

// toys/posix/kill.c

struct kill_data {
  char *s;
  struct arg_list *o;
};

// toys/posix/logger.c

struct logger_data {
  char *p, *t;
};

// toys/posix/ls.c

struct ls_data {
  long l;
  char *color;

  struct dirtree *files, *singledir;
  unsigned screen_width;
  int nl_title;
  char *escmore;
};

// toys/posix/mkdir.c

struct mkdir_data {
  char *m, *Z;
};

// toys/posix/mkfifo.c

struct mkfifo_data {
  char *m;
  char *Z;

  mode_t mode;
};

// toys/posix/nice.c

struct nice_data {
  long n;
};

// toys/posix/nl.c

struct nl_data {
  char *s, *n, *b;
  long w, l, v;

  // Count of consecutive blank lines for -l has to persist between files
  long lcount;
};

// toys/posix/od.c

struct od_data {
  struct arg_list *t;
  char *A;
  long N, w, j;

  int address_idx;
  unsigned types, leftover, star;
  char *buf; // Points to buffers[0] or buffers[1].
  char *bufs[2]; // Used to detect duplicate lines.
  off_t pos;
};

// toys/posix/paste.c

struct paste_data {
  char *d;

  int files;
};

// toys/posix/patch.c

struct patch_data {
  char *i, *d;
  long p;

  struct double_list *current_hunk;
  long oldline, oldlen, newline, newlen;
  long linenum;
  int context, state, filein, fileout, filepatch, hunknum;
  char *tempname;
};

// toys/posix/ps.c

struct ps_data {
  union {
    struct {
      struct arg_list *G, *g, *U, *u, *t, *s, *p, *O, *o, *P, *k;
    } ps;
    struct {
      long n, m, d, s;
      struct arg_list *u, *p, *o, *k, *O;
    } top;
    struct {
      char *L;
      struct arg_list *G, *g, *P, *s, *t, *U, *u;
      char *d;

      void *regexes, *snapshot;
      int signal;
      pid_t self, match;
    } pgrep;
  };

  struct ptr_len gg, GG, pp, PP, ss, tt, uu, UU;
  struct dirtree *threadparent;
  unsigned width, height;
  dev_t tty;
  void *fields, *kfields;
  long long ticks, bits, time;
  int kcount, forcek, sortpos;
  int (*match_process)(long long *slot);
  void (*show_process)(void *tb);
};

// toys/posix/renice.c

struct renice_data {
  long n;
};

// toys/posix/sed.c

struct sed_data {
  char *i;
  struct arg_list *f, *e;

  // processed pattern list
  struct double_list *pattern;

  char *nextline, *remember;
  void *restart, *lastregex;
  long nextlen, rememberlen, count;
  int fdout, noeol;
  unsigned xx;
  char delim;
};

// toys/posix/sort.c

struct sort_data {
  char *t;
  struct arg_list *k;
  char *o, *T, S;

  void *key_list;
  int linecount;
  char **lines;
};

// toys/posix/split.c

struct split_data {
  long l, b, a;

  char *outfile;
};

// toys/posix/strings.c

struct strings_data {
  long n;
  char *t;
};

// toys/posix/tail.c

struct tail_data {
  long n, c;

  int file_no, ffd, *files;
};

// toys/posix/tee.c

struct tee_data {
  void *outputs;
};

// toys/posix/touch.c

struct touch_data {
  char *t, *r, *d;
};

// toys/posix/ulimit.c

struct ulimit_data {
  long P;
};

// toys/posix/uniq.c

struct uniq_data {
  long w, s, f;

  long repeats;
};

// toys/posix/uudecode.c

struct uudecode_data {
  char *o;
};

// toys/posix/wc.c

struct wc_data {
  unsigned long totals[4];
};

// toys/posix/xargs.c

struct xargs_data {
  long s, n;
  char *E, *I;

  long entries, bytes;
  char delim;
};

extern union global_union {
	struct getprop_data getprop;
	struct log_data log;
	struct hello_data hello;
	struct skeleton_data skeleton;
	struct dmesg_data dmesg;
	struct hostname_data hostname;
	struct killall_data killall;
	struct md5sum_data md5sum;
	struct mknod_data mknod;
	struct mktemp_data mktemp;
	struct mount_data mount;
	struct passwd_data passwd;
	struct pidof_data pidof;
	struct seq_data seq;
	struct su_data su;
	struct umount_data umount;
	struct ftpget_data ftpget;
	struct ifconfig_data ifconfig;
	struct microcom_data microcom;
	struct netcat_data netcat;
	struct netstat_data netstat;
	struct ping_data ping;
	struct sntp_data sntp;
	struct tunctl_data tunctl;
	struct acpi_data acpi;
	struct base64_data base64;
	struct blockdev_data blockdev;
	struct chrt_data chrt;
	struct dos2unix_data dos2unix;
	struct fallocate_data fallocate;
	struct fmt_data fmt;
	struct free_data free;
	struct hexedit_data hexedit;
	struct hwclock_data hwclock;
	struct ionice_data ionice;
	struct login_data login;
	struct losetup_data losetup;
	struct lspci_data lspci;
	struct makedevs_data makedevs;
	struct mix_data mix;
	struct mkpasswd_data mkpasswd;
	struct mkswap_data mkswap;
	struct modinfo_data modinfo;
	struct nsenter_data nsenter;
	struct oneit_data oneit;
	struct setfattr_data setfattr;
	struct shred_data shred;
	struct stat_data stat;
	struct swapon_data swapon;
	struct switch_root_data switch_root;
	struct timeout_data timeout;
	struct truncate_data truncate;
	struct watch_data watch;
	struct xxd_data xxd;
	struct arp_data arp;
	struct arping_data arping;
	struct bc_data bc;
	struct bootchartd_data bootchartd;
	struct brctl_data brctl;
	struct crond_data crond;
	struct crontab_data crontab;
	struct dd_data dd;
	struct dhcp_data dhcp;
	struct dhcp6_data dhcp6;
	struct dhcpd_data dhcpd;
	struct diff_data diff;
	struct dumpleases_data dumpleases;
	struct expr_data expr;
	struct fdisk_data fdisk;
	struct fold_data fold;
	struct fsck_data fsck;
	struct getfattr_data getfattr;
	struct getty_data getty;
	struct groupadd_data groupadd;
	struct gzip_data gzip;
	struct host_data host;
	struct ip_data ip;
	struct ipcrm_data ipcrm;
	struct ipcs_data ipcs;
	struct klogd_data klogd;
	struct last_data last;
	struct lsof_data lsof;
	struct mke2fs_data mke2fs;
	struct modprobe_data modprobe;
	struct more_data more;
	struct openvt_data openvt;
	struct route_data route;
	struct sh_data sh;
	struct stty_data stty;
	struct sulogin_data sulogin;
	struct syslogd_data syslogd;
	struct tar_data tar;
	struct tcpsvd_data tcpsvd;
	struct telnet_data telnet;
	struct telnetd_data telnetd;
	struct tftp_data tftp;
	struct tftpd_data tftpd;
	struct tr_data tr;
	struct traceroute_data traceroute;
	struct useradd_data useradd;
	struct vi_data vi;
	struct wget_data wget;
	struct basename_data basename;
	struct chgrp_data chgrp;
	struct chmod_data chmod;
	struct cksum_data cksum;
	struct cmp_data cmp;
	struct cp_data cp;
	struct cpio_data cpio;
	struct cut_data cut;
	struct date_data date;
	struct df_data df;
	struct du_data du;
	struct env_data env;
	struct expand_data expand;
	struct file_data file;
	struct find_data find;
	struct grep_data grep;
	struct head_data head;
	struct iconv_data iconv;
	struct id_data id;
	struct kill_data kill;
	struct logger_data logger;
	struct ls_data ls;
	struct mkdir_data mkdir;
	struct mkfifo_data mkfifo;
	struct nice_data nice;
	struct nl_data nl;
	struct od_data od;
	struct paste_data paste;
	struct patch_data patch;
	struct ps_data ps;
	struct renice_data renice;
	struct sed_data sed;
	struct sort_data sort;
	struct split_data split;
	struct strings_data strings;
	struct tail_data tail;
	struct tee_data tee;
	struct touch_data touch;
	struct ulimit_data ulimit;
	struct uniq_data uniq;
	struct uudecode_data uudecode;
	struct wc_data wc;
	struct xargs_data xargs;
} this;
