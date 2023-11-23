This directory contains a port of the SimplePAMApp su to more
aggressively use libcap.

The Makefile builds a binary called `su` that registers with PAM as
the application `sucap`. We've provided a sample `/etc/pam.d/sucap`
file in this directory named `sucap.pamconfig`.

The point of developing this is to better test the full libcap
implementation, and to also provide a non-setuid-root worked example
for testing PAM interaction with libcap and pam_cap.so. The
expectations for `pam_unix.so` are that it includes this commit:


The original sources were found here:

https://kernel.org/pub/linux/libs/pam/pre/applications/SimplePAMApps-0.60.tar.gz

The SimplePAMApps contain the same License as libcap (they were
originally started by the same authors!). The credited Authors in the
above tarball were:

-  Andrew [G.] Morgan
-  Andrey V. Savochkin
-  Alexei V. Galatenko

The code in this present directory is freely adapted from the above
tar ball and is thus a derived work from that.

**NOTE** As of the time of writing, this adaptation is likely rife
  with bugs.

Finally, Andrew would like to apologize to Andrey for removing all of
the config support he worked to add all those decades ago..! I just
wanted to make a quick tester for a potential workaround for this
pam_cap issue:

-  https://bugzilla.kernel.org/show_bug.cgi?id=212945

Andrew G. Morgan <morgan@kernel.org>
2021-06-30
