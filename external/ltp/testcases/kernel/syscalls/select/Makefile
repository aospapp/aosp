#
#  Copyright (c) International Business Machines  Corp., 2001
#
#  This program is free software;  you can redistribute it and/or modify
#  it under the terms of the GNU General Public License as published by
#  the Free Software Foundation; either version 2 of the License, or
#  (at your option) any later version.
#
#  This program is distributed in the hope that it will be useful,
#  but WITHOUT ANY WARRANTY;  without even the implied warranty of
#  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See
#  the GNU General Public License for more details.
#
#  You should have received a copy of the GNU General Public License
#  along with this program;  if not, write to the Free Software
#  Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
#

top_srcdir		?= ../../../..

%_SYS__newselect: %_SYS__newselect.o
	$(LINK.o) $^ $(LOADLIBES) $(LDLIBS) -o $@
%_SYS_select: %_SYS_select.o
	$(LINK.o) $^ $(LOADLIBES) $(LDLIBS) -o $@
%_SYS_pselect6: %_SYS_pselect6.o
	$(LINK.o) $^ $(LOADLIBES) $(LDLIBS) -o $@

%_SYS__newselect.o: %.c select.h
	$(COMPILE.c) -DSYSCALL_SELECT__NEWSELECT $(OUTPUT_OPTION) $<
%_SYS_select.o: %.c select.h
	$(COMPILE.c) -DSYSCALL_SELECT_SELECT $(OUTPUT_OPTION) $<
%_SYS_pselect6.o: %.c select.h
	$(COMPILE.c) -DSYSCALL_SELECT_PSELECT6 $(OUTPUT_OPTION) $<

select01 select02 select03 select04: select.h
select01 select02 select03 select04: CFLAGS+=-DSYSCALL_SELECT_LIBC
select04: LDLIBS+=-lrt
select04_SYS_%: LDLIBS+=-lrt

MAKE_TARGETS := $(notdir $(patsubst %.c,%,$(wildcard $(abs_srcdir:%=%/)*.c))) \
 $(notdir $(patsubst %.c,%_SYS__newselect,$(wildcard $(abs_srcdir:%=%/)*.c))) \
 $(notdir $(patsubst %.c,%_SYS_select,$(wildcard $(abs_srcdir:%=%/)*.c)))     \
 $(notdir $(patsubst %.c,%_SYS_pselect6,$(wildcard $(abs_srcdir:%=%/)*.c)))

include $(top_srcdir)/include/mk/testcases.mk

include $(top_srcdir)/include/mk/generic_leaf_target.mk
