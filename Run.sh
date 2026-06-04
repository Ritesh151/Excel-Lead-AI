# Terminal 1
cd backend-node && npm run dev

# Terminal 2  
cd ai-python && python main.py server

# Start campaign (from any HTTP client)
curl -X POST http://localhost:3000/api/adb/start -H 'Content-Type: application/json' -d '{}'

# Check full pipeline health
curl http://localhost:3000/api/debug/pipeline
