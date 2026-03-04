# DocuMind
## AI Document Intelligence

CSYE 7374 Final Project - Roshan Shetty & Rithwik

## What This Project Does

DocuMind is a distributed AI-powered question-answering system for documents. Upload lecture notes, PDFs, or text files, then ask questions in natural language and get AI-generated answers based on your documents.

The system uses RAG (Retrieval-Augmented Generation) to find relevant content from uploaded files and feeds it to an LLM to generate accurate answers. It runs on an Akka Cluster for distributed processing and fault tolerance.

## Architecture Overview

### Technologies Used
- **Akka Cluster**: Distributed actor system (2 nodes for fault tolerance)
- **Spring Boot**: REST API for file uploads and queries
- **React**: Frontend UI
- **Word2Vec**: Text embeddings (300-dimensional vectors)
- **Qdrant**: Vector database for semantic search
- **OpenAI GPT-3.5**: Answer generation
- **Maven**: Build tool

### System Components

**Cluster Nodes (Ports 2551, 2552)**
- Each node runs 7 actors:
  - 1 OrchestratorActor (coordinates the RAG pipeline)
  - 4 SearchWorkerActors (handle vector search in parallel)
  - 1 LLMActor (calls OpenAI API)
  - 1 LoggingActor (centralized logging)

**REST API (Port 8080)**
- Spring Boot server that joins the cluster on port 2553
- Handles file uploads and user queries
- Discovers cluster actors using Akka Receptionist

**Frontend (Port 3000)**
- React UI for uploading files and asking questions
- Built with Vite

**External Services**
- Qdrant vector database (Port 6333)
- OpenAI API (requires API key)

## Prerequisites

Before running this project, make sure you have:

1. **Java 17 or higher**
   ```bash
   java -version
   ```

2. **Maven 3.6+**
   ```bash
   mvn -version
   ```

3. **Node.js and npm** (for frontend)
   ```bash
   node -v
   npm -v
   ```

4. **Docker** (for Qdrant)
   ```bash
   docker -v
   ```

5. **OpenAI API Key**
   - Get one from https://platform.openai.com/api-keys
   - Set it as environment variable:
     ```bash
     # Windows
     set OPENAI_API_KEY=your-key-here
     
     # Linux/Mac
     export OPENAI_API_KEY=your-key-here
     ```

6. **Word2Vec Model**
   - Download `GoogleNews-vectors-negative300-SLIM.bin` (1.5GB)
   - Place it in `backend/src/main/resources/`
   - Get it from: https://github.com/eyaler/word2vec-slim

## Setup Instructions

### 1. Clone and Build

```bash
git clone <your-repo-url>
cd FinalProject

# Build backend
cd backend
mvn clean install
cd ..

# Install frontend dependencies
cd frontend
npm install
cd ..
```

### 2. Start Qdrant (Vector Database)

Open a new terminal and run:
```bash
docker run -p 6333:6333 qdrant/qdrant
```

Keep this running. You should see "Qdrant is ready!" in the logs.

### 3. Start Cluster Node 1 (Seed Node)

Open a new terminal:
```bash
cd backend
mvn exec:java "-Dexec.args=2551"
```

Wait for: `[CLUSTER] [Node-2551] Spawned OrchestratorActor`

**Important**: This node MUST start first because it's the seed node.

### 4. Start Cluster Node 2

Open a new terminal:
```bash
cd backend
mvn exec:java "-Dexec.args=2552"
```

Wait for: `[CLUSTER] [Node-2552] Spawned OrchestratorActor`

You should see both nodes discover each other via gossip protocol.

### 5. Start REST API

Open a new terminal:
```bash
cd backend
mvn spring-boot:run
```

Wait for: `[REST-CLUSTER] Joined cluster as REST API client!`

The API will be available at `http://localhost:8080`

### 6. Start Frontend

Open a new terminal:
```bash
cd frontend
npm run dev
```

The UI will open at `http://localhost:3000`

## Using DocuMind

### 1. Upload Documents

- Click "📁 Upload Document" in the DocuMind UI
- Select a PDF or TXT file (course notes, lecture slides, research papers, etc.)
- Wait for "Document ready!" notification

DocuMind will:
- Split the document into chunks (5 sentences per chunk, 60% overlap)
- Convert each chunk to a 300-dimensional vector using Word2Vec
- Store vectors and metadata in Qdrant

### 2. Ask Questions

- Type a question in the input box: "What is an Akka actor?"
- Press Enter or click the send button (➤)
- DocuMind will:
  1. Convert your query to a vector
  2. Search Qdrant for the top 5 most similar chunks
  3. Re-rank results by keyword relevance
  4. Build a prompt with context and your question
  5. Send to OpenAI GPT-3.5 for answer generation
  6. Return the answer with source tracking

### 3. View Results

The answer will show:
- The AI-generated response
- Processing time
- Which cluster node handled the request
- Number of relevant chunks found

## Project Structure

```
DocuMind/
├── backend/
│   ├── src/main/java/.../
│   │   ├── actors/
│   │   │   ├── OrchestratorActor.java      # Coordinates RAG pipeline
│   │   │   ├── SearchWorkerActor.java      # Vector search workers
│   │   │   ├── LLMActor.java               # OpenAI integration
│   │   │   ├── LoggingActor.java           # Centralized logging
│   │   │   └── ClusterClientActor.java     # REST API cluster client
│   │   ├── services/
│   │   │   ├── EmbeddingService.java       # Word2Vec wrapper
│   │   │   ├── QdrantService.java          # Vector DB operations
│   │   │   └── LLMService.java             # OpenAI API calls
│   │   ├── controller/
│   │   │   ├── QuestionController.java     # Query endpoint
│   │   │   └── FileUploadController.java   # Upload endpoint
│   │   ├── config/
│   │   │   └── ClusterClientConfig.java    # Akka cluster setup
│   │   ├── messages/                        # Message classes
│   │   ├── ClusterNode.java                 # Cluster node entry point
│   │   └── WebApplication.java              # Spring Boot entry point
│   └── src/main/resources/
│       ├── application.conf                 # Akka cluster config
│       └── GoogleNews-vectors-negative300-SLIM.bin
├── frontend/
│   └── src/
│       ├── App.jsx                          # DocuMind UI component
│       └── main.jsx                         # Entry point
└── README.md
```

## Akka Communication Patterns in DocuMind

DocuMind demonstrates three key Akka communication patterns:

### 1. TELL (Fire-and-Forget)
```java
loggingActor.tell(new LogMessage("Query received"));
```
Used for logging - we don't need a response.

### 2. ASK (Request-Response)
```java
AskPattern.ask(orchestrator, 
    replyTo -> new ProcessQuery(question, replyTo),
    Duration.ofSeconds(60), scheduler);
```
Used by REST API - we need to wait for the answer.

### 3. FORWARD (Preserve Original Sender)
OrchestratorActor passes the original `replyTo` to LLMActor, so the response goes directly back to the REST controller without routing through the orchestrator again.

## Fault Tolerance Demo

DocuMind keeps working even if one node dies:

1. **Kill Node 2551**: Press Ctrl+C in the terminal running port 2551
2. **Check Logs**: Node 2552 detects it as UNREACHABLE
3. **Ask a Question**: System still works! Query goes to Node 2552
4. **Restart Node 2551**: Run `mvn exec:java "-Dexec.args=2551"` again
5. **Watch Recovery**: Node 2551 rejoins the cluster automatically

This works because:
- We have 2 nodes with redundant actors
- Akka Cluster uses gossip protocol to share state
- Receptionist automatically updates actor availability
- REST API round-robins between available orchestrators

## Common Issues

### Port Already in Use
**Problem**: `Address already in use: bind`

**Solution**: Kill the process using that port
```bash
# Windows
netstat -ano | findstr :2551
taskkill /PID <process-id> /F

# Linux/Mac
lsof -ti:2551 | xargs kill -9
```

### Qdrant Connection Failed
**Problem**: `Connection refused: localhost/127.0.0.1:6333`

**Solution**: Make sure Qdrant is running
```bash
docker ps  # Check if container is running
docker run -p 6333:6333 qdrant/qdrant  # Start if not running
```

### OpenAI API Error
**Problem**: `401 Unauthorized` or `Invalid API key`

**Solution**: Check your API key
```bash
# Verify it's set
echo %OPENAI_API_KEY%  # Windows
echo $OPENAI_API_KEY   # Linux/Mac

# Set it again if needed
set OPENAI_API_KEY=sk-...  # Windows
export OPENAI_API_KEY=sk-...  # Linux/Mac
```

### Word2Vec Model Not Found
**Problem**: `FileNotFoundException: GoogleNews-vectors-negative300-SLIM.bin`

**Solution**: 
1. Download the model from https://github.com/eyaler/word2vec-slim
2. Place it in `backend/src/main/resources/`
3. File size should be ~1.5GB

### No Orchestrators Available
**Problem**: REST API returns "No orchestrators available"

**Solution**: 
1. Make sure both cluster nodes (2551, 2552) are running
2. Wait 10-15 seconds for cluster formation
3. Check logs for "Orchestrator registered with Receptionist"
4. Restart nodes in order: 2551 first, then 2552

### Frontend Can't Connect to API
**Problem**: Frontend shows connection error

**Solution**:
1. Check Spring Boot is running on port 8080
2. Verify in browser: http://localhost:8080/api/health
3. Check CORS settings if accessing from different domain

## API Endpoints

### POST /api/ask
Submit a question
```bash
curl -X POST http://localhost:8080/api/ask \
  -H "Content-Type: application/json" \
  -d '{"question": "What is an Akka actor?"}'
```

### POST /api/upload
Upload a document
```bash
curl -X POST http://localhost:8080/api/upload \
  -F "file=@lecture.pdf"
```

### GET /api/health
Check system status
```bash
curl http://localhost:8080/api/health
```

### GET /api/cluster-status
Check cluster state
```bash
curl http://localhost:8080/api/cluster-status
```

### GET /api/info
Get system information
```bash
curl http://localhost:8080/api/info
```

## Performance Considerations

DocuMind is optimized for:
- **Chunk Size**: 5 sentences with 60% overlap balances context preservation vs storage
- **Top-K**: Retrieves top 5 chunks - more context but slower; fewer chunks but less info
- **Worker Count**: 4 workers per node allows parallel search while managing memory
- **Timeout**: 60 seconds for ASK pattern handles slow OpenAI responses
- **Memory**: Word2Vec SLIM (1.5GB) vs full model (3.5GB) - chose SLIM for 8GB RAM laptops

## Limitations

Current DocuMind limitations:
- Word2Vec doesn't understand technical terms well (e.g., "CSYE 7374" has no semantic meaning)
- Requires all nodes running for full fault tolerance (1 node = no redundancy)
- No persistence - uploaded files lost on restart (could add Akka Persistence)
- OpenAI API costs money - each query ~$0.002 for GPT-3.5
- Single Qdrant instance - not distributed (could use Qdrant cluster in production)

## Future Improvements for DocuMind

- Better embeddings (sentence-transformers, OpenAI embeddings)
- Akka Persistence for document state recovery
- More sophisticated re-ranking algorithms
- GraphRAG for entity relationships
- Multi-node Qdrant cluster for scalability
- Caching for frequently asked questions
- User authentication and query history
- Document version control and update detection

## Testing

Run unit tests:
```bash
cd backend
mvn test
```

Test RAG pipeline without cluster:
```bash
cd backend
mvn exec:java -Dexec.mainClass="com.northeastern.csye7374.finalproject.SimpleRAGTest"
```

## Authors

**DocuMind** - AI Document Intelligence System

Created by: Roshan Shetty & Rithwik

Course: CSYE 7374 - AI Agent Infrastructure  
Institution: Northeastern University  
Semester: Fall 2024

## License

This project is for educational purposes as part of CSYE 7374 coursework.
