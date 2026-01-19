import base64
import collections

try:
    with open('subscription_base64.txt', 'r', encoding='utf-8') as f:
        content = f.read().strip()
    
    # Check if content needs padding
    missing_padding = len(content) % 4
    if missing_padding:
        content += '=' * (4 - missing_padding)
        
    decoded = base64.b64decode(content).decode('utf-8')
    lines = [l.strip() for l in decoded.splitlines() if l.strip()]
    
    print(f"Total nodes found in Base64: {len(lines)}")
    
    protocols = collections.Counter()
    for line in lines:
        if '://' in line:
            proto = line.split('://')[0]
            protocols[proto] += 1
        else:
            protocols['unknown'] += 1
            
    print("Protocol counts:")
    for proto, count in protocols.items():
        print(f"  {proto}: {count}")
        
    print("\nFirst 5 nodes:")
    for line in lines[:5]:
        print(line)
        
except Exception as e:
    print(f"Error: {e}")
