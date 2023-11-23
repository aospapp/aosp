#!/bin/bash
# This script generates some C code for inclusion in the capsh binary.
# The Makefile generally only generates the .h code and compares it
# with the checked in code in the progs directory.

cat<<EOF
#ifdef CAPSHDOC
#error "don't include this twice"
#endif
#define CAPSHDOC

/*
 * A line by line explanation of each named capability value
 */
EOF

let x=0
while [ -f "../doc/values/${x}.txt" ]; do
    name=$(fgrep ",${x}}" ../libcap/cap_names.list.h|sed -e 's/{"//' -e 's/",/ = /' -e 's/},//')
    echo "static const char *explanation${x}[] = {  /* ${name} */"
    sed -e 's/"/\\"/g' -e 's/^/    "/' -e 's/$/",/' "../doc/values/${x}.txt"
    let x=1+${x}
    echo "    NULL"
    echo "};"
done

cat<<EOF
static const char **explanations[] = {
EOF
let y=0
while [ "${y}" -lt "${x}" ]; do
    echo "    explanation${y},"
    let y=1+${y}
done
cat<<EOF
};
#define CAPSH_DOC_LIMIT ${x}
EOF
