import React, { useState, useRef, useEffect } from 'react';

/**
 * DocuMind - AI Document Q&A System
 * Clean Blue Theme with Modern Design
 * 
 * @author Roshan Shetty & Rithwik
 * CSYE 7374 - AI Agent Infrastructure
 */

const API_URL = import.meta.env.VITE_API_URL || 'http://localhost:8080';

export default function App() {
  const [messages, setMessages] = useState([]);
  const [input, setInput] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  const [isUploading, setIsUploading] = useState(false);
  const [toast, setToast] = useState(null);
  const [uploadedDocs, setUploadedDocs] = useState([]);
  const messagesEndRef = useRef(null);
  const fileInputRef = useRef(null);

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  useEffect(() => {
    if (toast) {
      const timer = setTimeout(() => setToast(null), 4000);
      return () => clearTimeout(timer);
    }
  }, [toast]);

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (!input.trim() || isLoading || isUploading) return;

    const question = input.trim();
    setInput('');
    setMessages(prev => [...prev, { type: 'user', content: question }]);
    setIsLoading(true);

    try {
      const response = await fetch(`${API_URL}/api/ask`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ question })
      });
      const data = await response.json();
      setMessages(prev => [...prev, { 
        type: 'bot', 
        content: data.answer,
        responseTime: data.responseTime
      }]);
    } catch {
      setMessages(prev => [...prev, { 
        type: 'bot', 
        content: '⚠️ Connection error. Is the backend running?',
        isError: true
      }]);
    } finally {
      setIsLoading(false);
    }
  };

  const handleFileUpload = async (e) => {
    const file = e.target.files[0];
    if (!file) return;

    const ext = file.name.split('.').pop().toLowerCase();
    if (!['pdf', 'txt'].includes(ext)) {
      setToast({ type: 'error', message: 'Only PDF and TXT supported' });
      return;
    }
    if (file.size > 10 * 1024 * 1024) {
      setToast({ type: 'error', message: 'Max 10MB' });
      return;
    }

    setIsUploading(true);
    setToast({ type: 'info', message: `Processing "${file.name}"...` });

    try {
      const formData = new FormData();
      formData.append('file', file);
      const response = await fetch(`${API_URL}/api/upload`, { method: 'POST', body: formData });
      const data = await response.json();

      if (data.success) {
        setToast({ type: 'success', message: 'Document ready!' });
        setUploadedDocs(prev => [...prev, { name: file.name }]);
        setMessages(prev => [...prev, { type: 'system', content: `📄 "${file.name}" loaded — ask me anything!` }]);
      } else {
        setToast({ type: 'error', message: 'Upload failed' });
      }
    } catch {
      setToast({ type: 'error', message: 'Upload failed' });
    } finally {
      setIsUploading(false);
      if (fileInputRef.current) fileInputRef.current.value = '';
    }
  };

  const isDisabled = isLoading || isUploading;

  return (
    <div style={styles.app}>
    <div style={styles.container}>
        {/* Sidebar */}
        <aside style={styles.sidebar}>
          {/* Logo */}
          <div style={styles.logoSection}>
            <div style={styles.logo}>📑</div>
            <div>
              <h1 style={styles.brandName}>DocuMind</h1>
              <p style={styles.tagline}>AI Document Intelligence</p>
            </div>
          </div>

          {/* Upload */}
          <div style={styles.uploadSection}>
            <input type="file" ref={fileInputRef} onChange={handleFileUpload} accept=".pdf,.txt" hidden />
            <button 
              onClick={() => !isDisabled && fileInputRef.current?.click()}
              style={{...styles.uploadBtn, opacity: isDisabled ? 0.6 : 1}}
              disabled={isDisabled}
            >
              {isUploading ? '⏳ Processing...' : '📁 Upload Document'}
                  </button>
            <span style={styles.uploadHint}>PDF or TXT • Max 10MB</span>
          </div>

          {/* Documents */}
          {uploadedDocs.length > 0 && (
            <div style={styles.docsSection}>
              <div style={styles.sectionTitle}>
                <span>📚</span> Your Documents
              </div>
              <div style={styles.docsList} className="docsList">
                {uploadedDocs.map((d, i) => (
                  <div key={i} style={styles.docItem}>
                    <span>📄</span>
                    <span style={styles.docName}>{d.name}</span>
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* Spacer */}
          <div style={styles.spacer} />

          {/* Features */}
          <div style={styles.featuresSection}>
            <div style={styles.sectionTitle}>✨ Capabilities</div>
            <div style={styles.featuresList}>
              <div style={styles.featureItem}>🔍 Semantic Search</div>
              <div style={styles.featureItem}>🧠 Context-Aware AI</div>
              <div style={styles.featureItem}>⚡ Real-time Answers</div>
              <div style={styles.featureItem}>📊 Multi-doc Support</div>
            </div>
          </div>

          {/* Tech & Credits */}
          <div style={styles.footer}>
            <div style={styles.techBadges}>
              <span style={styles.badge}>Akka</span>
              <span style={styles.badge}>Word2Vec</span>
              <span style={styles.badge}>Qdrant</span>
              <span style={styles.badge}>GPT</span>
            </div>
            <p style={styles.credits}>CSYE 7374 • Roshan & Rithwik</p>
          </div>
        </aside>

        {/* Main Chat */}
        <main style={styles.main}>
          <div style={styles.chatArea}>
            {messages.length === 0 ? (
              <div style={styles.welcome}>
                <div style={styles.welcomeIcon}>🚀</div>
                <h2 style={styles.welcomeTitle}>What would you like to know?</h2>
                <p style={styles.welcomeDesc}>Upload a document and ask questions in natural language</p>
                <div style={styles.exampleBtns}>
                  {["Summarize this document", "What are the key points?", "Explain in simple terms"].map((q, i) => (
                    <div key={i} style={styles.exampleChip}>{q}</div>
                  ))}
                </div>
              </div>
            ) : (
          <div style={styles.messages}>
                {messages.map((m, i) => (
                  <div key={i} style={{...styles.msgRow, justifyContent: m.type === 'user' ? 'flex-end' : 'flex-start'}}>
                    {m.type !== 'user' && (
                      <div style={{...styles.avatar, ...(m.type === 'system' ? styles.sysAvatar : styles.botAvatar)}}>
                        {m.type === 'system' ? '✅' : '🤖'}
                      </div>
                    )}
                <div style={{
                  ...styles.bubble,
                      ...(m.type === 'user' ? styles.userBubble : m.type === 'system' ? styles.sysBubble : styles.botBubble),
                      ...(m.isError && styles.errBubble)
                }}>
                      <p style={styles.msgText}>{m.content}</p>
                      {m.responseTime && <span style={styles.msgTime}>⏱️ {m.responseTime.toFixed(2)}s</span>}
                  </div>
                    {m.type === 'user' && (
                      <div style={{...styles.avatar, ...styles.userAvatar}}>👤</div>
                    )}
              </div>
            ))}
            {isLoading && (
              <div style={styles.msgRow}>
                    <div style={{...styles.avatar, ...styles.botAvatar}}>🤖</div>
                <div style={{...styles.bubble, ...styles.botBubble}}>
                      <div style={styles.typing}>
                        <span style={styles.dot}></span>
                        <span style={{...styles.dot, animationDelay: '0.2s'}}></span>
                        <span style={{...styles.dot, animationDelay: '0.4s'}}></span>
                  </div>
                </div>
              </div>
            )}
            <div ref={messagesEndRef} />
          </div>
            )}
          </div>

        {/* Input */}
          <div style={styles.inputArea}>
            <form onSubmit={handleSubmit} style={styles.inputForm}>
            <input
              value={input}
              onChange={(e) => setInput(e.target.value)}
                placeholder="Ask about your documents..."
              style={styles.input}
                disabled={isDisabled}
            />
              <button 
                type="submit" 
                style={{...styles.sendBtn, opacity: !input.trim() || isDisabled ? 0.5 : 1}} 
                disabled={!input.trim() || isDisabled}
              >
                ➤
            </button>
          </form>
          </div>
        </main>
      </div>

      {/* Toast */}
      {toast && (
        <div style={{
          ...styles.toast,
          background: toast.type === 'error' ? '#dc2626' : toast.type === 'success' ? '#16a34a' : '#2563eb'
        }} onClick={() => setToast(null)}>
          {toast.type === 'success' && '✅ '}{toast.type === 'error' && '❌ '}{toast.type === 'info' && '⏳ '}
          {toast.message}
        </div>
      )}

      <style>{`
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; }
        input:focus { outline: none; border-color: #3b82f6 !important; }
        @keyframes bounce { 0%, 80%, 100% { transform: translateY(0); } 40% { transform: translateY(-6px); } }
        
        /* Custom scrollbar for documents list */
        .docsList::-webkit-scrollbar {
          width: 6px;
        }
        .docsList::-webkit-scrollbar-track {
          background: ${C.surface};
          border-radius: 3px;
        }
        .docsList::-webkit-scrollbar-thumb {
          background: ${C.border};
          border-radius: 3px;
        }
        .docsList::-webkit-scrollbar-thumb:hover {
          background: ${C.textMuted};
        }
      `}</style>
    </div>
  );
}

// CLEAN BLUE COLOR SCHEME
const C = {
  bg: '#0f1419',
  sidebar: '#15202b',
  surface: '#192734',
  border: '#38444d',
  text: '#ffffff',
  textMuted: '#8899a6',
  accent: '#1d9bf0',
  accentHover: '#1a8cd8',
  success: '#00ba7c',
  error: '#f4212e'
};

const styles = {
  app: {
    minHeight: '100vh',
    background: C.bg,
    color: C.text
  },
  container: {
    display: 'flex',
    height: '100vh',
    maxWidth: '1300px',
    margin: '0 auto'
  },

  // Sidebar
  sidebar: {
    width: '280px',
    background: C.sidebar,
    borderRight: `1px solid ${C.border}`,
    padding: '20px',
    display: 'flex',
    flexDirection: 'column',
    gap: '20px'
  },
  logoSection: {
    display: 'flex',
    alignItems: 'center',
    gap: '12px',
    paddingBottom: '16px',
    borderBottom: `1px solid ${C.border}`
  },
  logo: {
    width: '44px',
    height: '44px',
    background: `linear-gradient(135deg, ${C.accent}, #60a5fa)`,
    borderRadius: '12px',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    fontSize: '22px'
  },
  brandName: {
    fontSize: '20px',
    fontWeight: 700,
    color: C.text
  },
  tagline: {
    fontSize: '12px',
    color: C.textMuted
  },

  // Upload
  uploadSection: {
    display: 'flex',
    flexDirection: 'column',
    gap: '8px'
  },
  uploadBtn: {
    padding: '14px 16px',
    background: C.accent,
    border: 'none',
    borderRadius: '25px',
    color: '#fff',
    fontSize: '15px',
    fontWeight: 600,
    cursor: 'pointer',
    transition: 'background 0.2s'
  },
  uploadHint: {
    fontSize: '12px',
    color: C.textMuted,
    textAlign: 'center'
  },

  // Documents
  docsSection: {
    display: 'flex',
    flexDirection: 'column',
    gap: '10px',
    minHeight: 0 // Important for flex scrolling
  },
  sectionTitle: {
    display: 'flex',
    alignItems: 'center',
    gap: '8px',
    fontSize: '14px',
    fontWeight: 600,
    color: C.textMuted,
    flexShrink: 0 // Prevent title from shrinking
  },
  docsList: {
    display: 'flex',
    flexDirection: 'column',
    gap: '6px',
    maxHeight: '180px', // Show ~3 documents at once
    overflowY: 'auto', // Enable scrolling
    paddingRight: '4px' // Space for scrollbar
  },
  docItem: {
    display: 'flex',
    alignItems: 'center',
    gap: '10px',
    padding: '10px 12px',
    background: C.surface,
    borderRadius: '10px',
    fontSize: '13px'
  },
  docName: {
    overflow: 'hidden',
    textOverflow: 'ellipsis',
    whiteSpace: 'nowrap'
  },

  spacer: { flex: 1 },

  // Features
  featuresSection: {
    display: 'flex',
    flexDirection: 'column',
    gap: '10px'
  },
  featuresList: {
    display: 'flex',
    flexDirection: 'column',
    gap: '6px'
  },
  featureItem: {
    fontSize: '13px',
    color: C.textMuted,
    padding: '6px 0'
  },

  // Footer
  footer: {
    paddingTop: '16px',
    borderTop: `1px solid ${C.border}`
  },
  techBadges: {
    display: 'flex',
    flexWrap: 'wrap',
    gap: '6px',
    marginBottom: '10px'
  },
  badge: {
    padding: '4px 10px',
    background: C.surface,
    border: `1px solid ${C.border}`,
    borderRadius: '12px',
    fontSize: '11px',
    color: C.textMuted
  },
  credits: {
    fontSize: '11px',
    color: C.textMuted
  },

  // Main
  main: {
    flex: 1,
    display: 'flex',
    flexDirection: 'column',
    overflow: 'hidden'
  },
  chatArea: {
    flex: 1,
    overflow: 'auto',
    padding: '24px'
  },

  // Welcome
  welcome: {
    height: '100%',
    display: 'flex',
    flexDirection: 'column',
    alignItems: 'center',
    justifyContent: 'center',
    textAlign: 'center'
  },
  welcomeIcon: {
    fontSize: '56px',
    marginBottom: '20px'
  },
  welcomeTitle: {
    fontSize: '28px',
    fontWeight: 700,
    marginBottom: '10px'
  },
  welcomeDesc: {
    fontSize: '16px',
    color: C.textMuted,
    marginBottom: '30px'
  },
  exampleBtns: {
    display: 'flex',
    flexWrap: 'wrap',
    gap: '10px',
    justifyContent: 'center'
  },
  exampleChip: {
    padding: '10px 18px',
    background: C.surface,
    border: `1px solid ${C.border}`,
    borderRadius: '20px',
    color: C.textMuted,
    fontSize: '14px'
  },

  // Messages
  messages: {
    display: 'flex',
    flexDirection: 'column',
    gap: '16px'
  },
  msgRow: {
    display: 'flex',
    alignItems: 'flex-start',
    gap: '12px'
  },
  avatar: {
    width: '36px',
    height: '36px',
    borderRadius: '50%',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    fontSize: '18px',
    flexShrink: 0
  },
  userAvatar: {
    background: C.accent
  },
  botAvatar: {
    background: C.surface,
    border: `1px solid ${C.border}`
  },
  sysAvatar: {
    background: C.success
  },
  bubble: {
    maxWidth: '70%',
    padding: '14px 18px',
    borderRadius: '18px',
    fontSize: '15px',
    lineHeight: 1.5
  },
  userBubble: {
    background: C.accent,
    color: '#fff',
    borderBottomRightRadius: '4px'
  },
  botBubble: {
    background: C.surface,
    border: `1px solid ${C.border}`,
    borderBottomLeftRadius: '4px'
  },
  sysBubble: {
    background: 'rgba(0,186,124,0.15)',
    border: '1px solid rgba(0,186,124,0.3)',
    color: '#00ba7c'
  },
  errBubble: {
    background: 'rgba(244,33,46,0.15)',
    borderColor: 'rgba(244,33,46,0.3)'
  },
  msgText: {
    margin: 0
  },
  msgTime: {
    display: 'block',
    fontSize: '12px',
    color: C.textMuted,
    marginTop: '6px'
  },
  typing: {
    display: 'flex',
    gap: '4px'
  },
  dot: {
    width: '8px',
    height: '8px',
    background: C.textMuted,
    borderRadius: '50%',
    animation: 'bounce 1.4s infinite ease-in-out'
  },

  // Input
  inputArea: {
    padding: '16px 24px 24px',
    borderTop: `1px solid ${C.border}`
  },
  inputForm: {
    display: 'flex',
    gap: '12px'
  },
  input: {
    flex: 1,
    padding: '14px 20px',
    background: C.surface,
    border: `1px solid ${C.border}`,
    borderRadius: '25px',
    color: C.text,
    fontSize: '15px'
  },
  sendBtn: {
    width: '50px',
    height: '50px',
    background: C.accent,
    border: 'none',
    borderRadius: '50%',
    color: '#fff',
    fontSize: '20px',
    cursor: 'pointer'
  },

  // Toast
  toast: {
    position: 'fixed',
    bottom: '24px',
    left: '50%',
    transform: 'translateX(-50%)',
    padding: '12px 24px',
    borderRadius: '25px',
    color: '#fff',
    fontSize: '14px',
    fontWeight: 500,
    boxShadow: '0 4px 20px rgba(0,0,0,0.3)'
  }
};
