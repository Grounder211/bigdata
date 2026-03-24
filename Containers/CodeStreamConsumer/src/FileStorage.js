class FileStorage {
    static #myInstance = null;
    static getInstance() {
        FileStorage.#myInstance = FileStorage.#myInstance || new FileStorage();
        return FileStorage.#myInstance;
    }

    #myFiles = [];
    #myFileNames = [];
    #myNumberOfFiles = 0;

    constructor() {
    }

    get numberOfFiles() { return this.#myNumberOfFiles; }
    get filenames() { return this.#myFileNames; }

    isFileProcessed(fileName) {
    // Return true if filename already stored
    return this.#myFileNames.includes(fileName);
    }

    storeFile(file) {
        if (!this.isFileProcessed(file.name)) {
            this.#myFileNames.push(file.name);
            this.#myNumberOfFiles++;

            // store minimal metadata only
            const meta = {
                name: file.name,
                chunks: file.chunks, // keep chunk lineNumbers but not full contents (ChunkIndex has chunk content)
            };
            this.#myFiles.push(meta);
        }

        return file;
    }

    * getAllFiles() {
        // FUTURE Convert this to use this.#myFileNames to fetch each file from a database instead.
        // then use yield to release each file to where it is going to be used.
        for (let f of this.#myFiles) {
            yield f;
        }
    }
}

module.exports = FileStorage;
