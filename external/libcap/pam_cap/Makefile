# simple make file for the pam_cap module

topdir=$(shell pwd)/..
include ../Make.Rules

all: pam_cap.so
	$(MAKE) testlink

install: all
	mkdir -p -m 0755 $(FAKEROOT)$(LIBDIR)/security
	install -m 0755 pam_cap.so $(FAKEROOT)$(LIBDIR)/security

../libcap/loader.txt:
	$(MAKE) -C ../libcap loader.txt

execable.o: execable.c ../libcap/execable.h ../libcap/loader.txt
	$(CC) $(CFLAGS) $(IPATH) -DLIBCAP_VERSION=\"libcap-$(VERSION).$(MINOR)\" -DSHARED_LOADER=\"$(shell cat ../libcap/loader.txt)\" -c execable.c -o $@

pam_cap.so: pam_cap.o execable.o pam_cap_linkopts
	cat pam_cap_linkopts | xargs -e $(LD) -o $@ pam_cap.o execable.o $(LIBCAPLIB) $(LDFLAGS)

# Some distributions force link everything at compile time, and don't
# take advantage of libpam's dlopen runtime options to resolve ill
# defined symbols from its own linkage as needed. (As the original
# author of that part of libpam, I consider this force linking
# premature optimization.) We debugged its consequences to pam_cap.so
# as part of:
#
#   https://bugzilla.kernel.org/show_bug.cgi?id=214023
#
# If the current build environment is one of those, extend the link
# options for pam_cap.so to force linkage against libpam and the
# gazillion other things libpam is linked against...
pam_cap_linkopts: lazylink.so
	echo "-Wl,-e,__so_start" > $@
	./lazylink.so || echo "-lpam" >> $@

lazylink.so: lazylink.c ../libcap/execable.h ../libcap/loader.txt
	$(LD) -o $@ $(CFLAGS) $(IPATH) lazylink.c -DSHARED_LOADER=\"$(shell cat ../libcap/loader.txt)\" $(LDFLAGS) -Wl,-e,__so_start

pam_cap.o: pam_cap.c
	$(CC) $(CFLAGS) $(IPATH) -c $< -o $@

../libcap/libcap.a:
	$(MAKE) -C ../libcap libcap.a

test_pam_cap: test_pam_cap.c pam_cap.c ../libcap/libcap.a
	$(CC) $(CFLAGS) $(IPATH) -o $@ test_pam_cap.c $(LIBCAPLIB) $(LDFLAGS) --static

testlink: test.c pam_cap.o
	$(CC) $(CFLAGS) -o $@ $+ -lpam -ldl $(LIBCAPLIB) $(LDFLAGS)

test: testlink test_pam_cap pam_cap.so
	$(MAKE) testlink
	./test_pam_cap
	LD_LIBRARY_PATH=../libcap ./pam_cap.so
	LD_LIBRARY_PATH=../libcap ./pam_cap.so --help
	@echo "module can be run as an executable!"

sudotest: test test_pam_cap
	sudo ./test_pam_cap root 0x0 0x0 0x0 config=./capability.conf
	sudo ./test_pam_cap root 0x0 0x0 0x0 config=./sudotest.conf
	sudo ./test_pam_cap alpha 0x0 0x0 0x0 config=./capability.conf
	sudo ./test_pam_cap alpha 0x0 0x1 0x80 config=./sudotest.conf
	sudo ./test_pam_cap beta 0x0 0x1 0x0 config=./sudotest.conf
	sudo ./test_pam_cap gamma 0x0 0x0 0x81 config=./sudotest.conf
	sudo ./test_pam_cap delta 0x41 0x80 0x41 config=./sudotest.conf

clean:
	rm -f *.o *.so testlink lazylink.so test_pam_cap pam_cap_linkopts *~
