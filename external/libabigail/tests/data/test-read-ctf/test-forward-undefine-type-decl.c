/* Test undefined forward declarations
 * gcc -gctf -c test-forward-undefine-type-decl.c -o \
 *    test-forward-undefine-type-decl.o
 */

struct key_type;
union  key_utype;
typedef void (*key_restrict_link_func_t)(struct key_type *type,
                                         union key_utype *utype);

struct key_restriction {
 key_restrict_link_func_t check;
 struct key_type *keytype;
 union key_utype *keyutype;
};

struct key_restriction k;
