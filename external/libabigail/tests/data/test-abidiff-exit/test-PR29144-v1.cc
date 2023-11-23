struct A {
  int x;
};

struct B {
  int y;
};

struct C {
  int z;
};

struct D: B, A, C {
  int d;
};

D order;

