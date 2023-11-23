#include "libcap.h"

static cap_value_t top;

static int cf(cap_value_t x) {
    return top - x - 1;
}

static int test_cap_bits(void) {
    static cap_value_t vs[] = {
	5, 6, 11, 12, 15, 16, 17, 38, 41, 63, 64, __CAP_MAXBITS+3, 0, -1
    };
    int failed = 0;
    cap_value_t i;
    for (i = 0; vs[i] >= 0; i++) {
	cap_value_t ans;

	top = i;
	_binary_search(ans, cf, 0, __CAP_MAXBITS, 0);
	if (ans != top) {
	    if (top > __CAP_MAXBITS && ans == __CAP_MAXBITS) {
	    } else {
		printf("test_cap_bits miscompared [%d] top=%d - got=%d\n",
		       i, top, ans);
		failed = -1;
	    }
	}
    }
    return failed;
}

static int test_cap_flags(void) {
    cap_t c, d;
    cap_flag_t f = CAP_INHERITABLE, t;
    cap_value_t v;

    c = cap_init();
    if (c == NULL) {
	printf("test_flags failed to allocate a set\n");
	return -1;
    }

    for (v = 0; v < __CAP_MAXBITS; v += 3) {
	if (cap_set_flag(c, CAP_INHERITABLE, 1, &v, CAP_SET)) {
	    printf("unable to set inheritable bit %d\n", v);
	    return -1;
	}
    }

    d = cap_dup(c);
    for (t = CAP_EFFECTIVE; t <= CAP_INHERITABLE; t++) {
	if (cap_fill(c, t, f)) {
	    printf("cap_fill failed %d -> %d\n", f, t);
	    return -1;
	}
	if (cap_clear_flag(c, f)) {
	    printf("cap_fill unable to clear flag %d\n", f);
	    return -1;
	}
	f = t;
    }
    if (cap_compare(c, d)) {
	printf("permuted cap_fill()ing failed to perform net no-op\n");
	return -1;
    }
    cap_free(d);
    cap_free(c);
    return 0;
}

int main(int argc, char **argv) {
    int result = 0;

    result = test_cap_bits() | result;
    result = test_cap_flags() | result;

    if (result) {
	printf("cap_test FAILED\n");
	exit(1);
    }
    printf("cap_test PASS\n");
    exit(0);
}
