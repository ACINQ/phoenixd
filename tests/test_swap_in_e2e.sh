#!/bin/bash
# Interactive swap-in E2E test for phoenixd on testnet
set -e

BASE_URL="http://localhost:9740"
PASSWORD=$(grep http-password ~/.phoenix/phoenix.conf | head -1 | cut -d= -f2)

echo "=== Step 1: Get swap-in address ==="
RESULT=$(curl -s -u ":$PASSWORD" "$BASE_URL/getswapinaddress")
ADDRESS=$(echo "$RESULT" | jq -r '.address')
echo "Swap-in address: $ADDRESS"
echo ""
if command -v qrencode &> /dev/null; then
    echo "Scan this QR code with your phone and send testnet coins:"
    qrencode -t ANSIUTF8 "bitcoin:$ADDRESS"
else
    echo "Install qrencode for QR display, or send testnet coins to: $ADDRESS"
fi
echo ""

echo "=== Step 2: Waiting for funds... ==="
echo "Press Enter after you've sent the transaction..."
read

echo "Polling swap-in wallet balance..."
while true; do
    BAL=$(curl -s -u ":$PASSWORD" "$BASE_URL/swapinwalletbalance")
    CONFIRMED=$(echo "$BAL" | jq '.totalBalanceSat')
    UNCONFIRMED=$(echo "$BAL" | jq '.unconfirmedBalanceSat')
    echo "  total: $CONFIRMED sat | unconfirmed: $UNCONFIRMED sat"
    if [ "$UNCONFIRMED" -gt 0 ] 2>/dev/null || [ "$CONFIRMED" -gt 0 ] 2>/dev/null; then
        echo "Funds detected!"
        break
    fi
    sleep 10
done

echo ""
echo "=== Step 3: Wait for confirmation ==="
echo "Waiting for at least 1 confirmation..."
while true; do
    BAL=$(curl -s -u ":$PASSWORD" "$BASE_URL/swapinwalletbalance")
    CONFIRMED=$(echo "$BAL" | jq '.totalBalanceSat')
    UNCONFIRMED=$(echo "$BAL" | jq '.unconfirmedBalanceSat')
    CONFIRMED_ONLY=$((CONFIRMED - UNCONFIRMED))
    echo "  confirmed: $CONFIRMED_ONLY sat (total: $CONFIRMED, unconfirmed: $UNCONFIRMED)"
    if [ "$CONFIRMED_ONLY" -gt 0 ] 2>/dev/null; then
        echo "Confirmed balance: $CONFIRMED_ONLY sat"
        break
    fi
    sleep 30
done

echo ""
echo "=== Step 4: Splice-in to channel ==="
echo "Splicing $CONFIRMED sat into channel..."
SPLICE_RESULT=$(curl -s -u ":$PASSWORD" -X POST "$BASE_URL/splicein" \
    -d "amountSat=$CONFIRMED&feerateSatByte=2")
echo "Splice result: $SPLICE_RESULT"

echo ""
echo "=== Step 5: Verify channel balance ==="
curl -s -u ":$PASSWORD" "$BASE_URL/getbalance" | jq .

echo ""
echo "=== Step 6: Splice-out (send back on-chain) ==="
read -p "Enter your return Bitcoin address: " RETURN_ADDR
SEND_RESULT=$(curl -s -u ":$PASSWORD" -X POST "$BASE_URL/sendtoaddress" \
    -d "amountSat=$((CONFIRMED - 1000))&address=$RETURN_ADDR&feerateSatByte=2")
echo "Send result: $SEND_RESULT"

echo ""
echo "=== Done! ==="
echo "Final balance:"
curl -s -u ":$PASSWORD" "$BASE_URL/getbalance" | jq .
