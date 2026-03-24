const fs = require('fs/promises');
const path = require('path');

// Persistent JSONL-backed chunk index. Each line: {key,name,startLine}
class ChunkIndex {
    static #instance = null;
    static getInstance() { ChunkIndex.#instance = ChunkIndex.#instance || new ChunkIndex(); return ChunkIndex.#instance; }

    constructor() {
        this.index = new Map();
        this.filePath = path.resolve(__dirname, '..', 'data', 'chunkindex.jsonl');
        // ensure directory exists
        fs.mkdir(path.dirname(this.filePath), { recursive: true }).catch(() => {});
        // load existing file if present
        this._loaded = this._loadFromFile();
    }

    static hashString(s) {
        let h = 2166136261 >>> 0;
        for (let i = 0; i < s.length; i++) {
            h ^= s.charCodeAt(i);
            h = Math.imul(h, 16777619) >>> 0;
        }
        return h.toString(16);
    }

    async _loadFromFile() {
        try {
            const stat = await fs.stat(this.filePath).catch(() => null);
            if (!stat) return;
            const data = await fs.readFile(this.filePath, 'utf8');
            if (!data) return;
            const lines = data.split('\n');
            for (let line of lines) {
                if (!line) continue;
                try {
                    const obj = JSON.parse(line);
                    const arr = this.index.get(obj.key) || [];
                    arr.push({ name: obj.name, startLine: obj.startLine, chunk: obj.chunk });
                    this.index.set(obj.key, arr);
                } catch (e) {
                    // ignore malformed
                }
            }
        } catch (e) {
            // ignore
        }
    }

    async add(name, startLine, chunk) {
        await this._loaded;
        const chunkStr = chunk.map(l => l.getContent()).join('\n');
        const key = ChunkIndex.hashString(chunkStr);
        const arr = this.index.get(key) || [];
        arr.push({ name, startLine, chunk: chunkStr });
        this.index.set(key, arr);
        const obj = { key, name, startLine, chunk: chunkStr };
        const line = JSON.stringify(obj) + '\n';
        // append to file (fire-and-forget)
        fs.appendFile(this.filePath, line).catch(err => console.log('ChunkIndex append error', err));
    }

    async lookup(chunk) {
        await this._loaded;
        const key = ChunkIndex.hashString(chunk.map(l => l.getContent()).join('\n'));
        return this.index.get(key) || [];
    }

    async clear() {
        this.index.clear();
        await fs.writeFile(this.filePath, '');
    }
}

module.exports = ChunkIndex;
