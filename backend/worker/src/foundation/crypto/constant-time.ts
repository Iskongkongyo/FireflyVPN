const encoder = new TextEncoder();

export function constantTimeEqual(left: string, right: string): boolean {
  const a = encoder.encode(left);
  const b = encoder.encode(right);
  const length = Math.max(a.length, b.length, 1);
  let difference = a.length ^ b.length;

  for (let index = 0; index < length; index += 1) {
    difference |= (a[index % Math.max(a.length, 1)] ?? 0)
      ^ (b[index % Math.max(b.length, 1)] ?? 0);
  }
  return difference === 0;
}
