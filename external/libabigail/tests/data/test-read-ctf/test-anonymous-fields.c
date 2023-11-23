/*
 * test single var-decl in anonymous struct/union
 * gcc -gctf -c tests/data/test-read- ctf/test-anonymous-fields.c \
 *    -o tests/data/test-read-ctf/test-anonymous-fields.o
*/
struct uprobe_task {
 union {
  struct {
   unsigned long vaddr;
  };

  struct {
   int dup_xol_work;
  };
 };
};

struct uprobe_task t;
