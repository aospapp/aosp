#
# NOTE the built tests are all designed to be run from this
# working directory when built DYNAMIC=yes. That is, they
# link to the shared libraries in ../libcap/ .
#
topdir=$(shell pwd)/..
include ../Make.Rules
#

all:
	$(MAKE) libcap_launch_test uns_test
ifeq ($(PTHREADS),yes)
	$(MAKE) psx_test libcap_psx_test libcap_psx_launch_test
endif

install: all

ifeq ($(DYNAMIC),yes)
LINKEXTRA=-Wl,-rpath,../libcap
DEPS=../libcap/libcap.so
ifeq ($(PTHREADS),yes)
DEPS += ../libcap/libpsx.so
endif
else
LDSTATIC = --static
DEPS=../libcap/libcap.a
ifeq ($(PTHREADS),yes)
DEPS +=  ../libcap/libpsx.a
endif
endif

../libcap/libcap.so:
	$(MAKE) -C ../libcap libcap.so

../libcap/libcap.a:
	$(MAKE) -C ../libcap libcap.a

ifeq ($(PTHREADS),yes)
../libcap/libpsx.so:
	$(MAKE) -C ../libcap libpsx.so

../libcap/libpsx.a:
	$(MAKE) -C ../libcap libpsx.a
endif

../progs/tcapsh-static:
	$(MAKE) -C ../progs tcapsh-static

test:
ifeq ($(PTHREADS),yes)
	$(MAKE) run_psx_test run_libcap_psx_test
endif

sudotest: test
	$(MAKE) run_uns_test
	$(MAKE) run_libcap_launch_test
ifeq ($(PTHREADS),yes)
	$(MAKE) run_libcap_psx_launch_test run_exploit_test
endif

# unprivileged
run_psx_test: psx_test
	./psx_test

psx_test: psx_test.c $(DEPS)
	$(CC) $(CFLAGS) $(IPATH) $< -o $@ $(LINKEXTRA) $(LIBPSXLIB) $(LDSTATIC)

run_libcap_psx_test: libcap_psx_test
	./libcap_psx_test

libcap_psx_test: libcap_psx_test.c $(DEPS)
	$(CC) $(CFLAGS) $(IPATH) $< -o $@ $(LINKEXTRA) $(LIBCAPLIB) $(LIBPSXLIB) $(LDSTATIC)

# privileged
uns_test: uns_test.c $(DEPS)
	$(CC) $(CFLAGS) $(IPATH) $< -o $@ $(LINKEXTRA) $(LIBCAPLIB) $(LDSTATIC)

run_uns_test: uns_test
	echo exit | sudo ./uns_test

run_libcap_launch_test: libcap_launch_test noop ../progs/tcapsh-static
	sudo ./libcap_launch_test

run_libcap_psx_launch_test: libcap_psx_launch_test ../progs/tcapsh-static
	sudo ./libcap_psx_launch_test

libcap_launch_test: libcap_launch_test.c $(DEPS)
	$(CC) $(CFLAGS) $(IPATH) $< -o $@ $(LINKEXTRA) $(LIBCAPLIB) $(LDSTATIC)

# This varies only slightly from the above insofar as it currently
# only links in the pthreads fork support. TODO() we need to change
# the source to do something interesting with pthreads.
libcap_psx_launch_test: libcap_launch_test.c $(DEPS)
	$(CC) $(CFLAGS) $(IPATH) -DWITH_PTHREADS $< -o $@ $(LINKEXTRA) $(LIBCAPLIB) $(LIBPSXLIB) $(LDSTATIC)


# This test demonstrates that libpsx is needed to secure multithreaded
# programs that link against libcap.
run_exploit_test: exploit noexploit
	@echo exploit should succeed
	sudo ./exploit ; if [ $$? -ne 0 ]; then exit 0; else exit 1 ; fi
	@echo exploit should fail
	sudo ./noexploit ; if [ $$? -eq 0 ]; then exit 0; else exit 1 ; fi

exploit.o: exploit.c
	$(CC) $(CFLAGS) $(IPATH) -c $<

exploit: exploit.o $(DEPS)
	$(CC) $(CFLAGS) $(IPATH) $< -o $@ $(LINKEXTRA) $(LIBCAPLIB) -lpthread $(LDSTATIC)

# Note, for some reason, the order of libraries is important to avoid
# the exploit working for dynamic linking.
noexploit: exploit.o $(DEPS)
	$(CC) $(CFLAGS) $(IPATH) $< -o $@ $(LINKEXTRA) $(LIBPSXLIB) $(LIBCAPLIB) $(LDSTATIC)

# This one runs in a chroot with no shared library files.
noop: noop.c
	$(CC) $(CFLAGS) $< -o $@ --static

clean:
	rm -f psx_test libcap_psx_test libcap_launch_test uns_test *~
	rm -f libcap_launch_test libcap_psx_launch_test core noop
	rm -f exploit noexploit exploit.o
