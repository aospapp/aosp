struct A {
  int x;
};

struct B {
  int y;
};

struct C {
  int z;
};

struct D: A, B, C {
  int d;
};

D order;
