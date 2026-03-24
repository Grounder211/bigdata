const emptyLine = /^\s*$/;
const oneLineComment = /\/\/.*/;
const oneLineMultiLineComment = /\/\*.*?\*\//; 
const openMultiLineComment = /\/\*+[^\*\/]*$/;
const closeMultiLineComment = /^[\*\/]*\*+\//;

const SourceLine = require('./SourceLine');
const FileStorage = require('./FileStorage');
const Clone = require('./Clone');
const ChunkIndex = require('./ChunkIndex');

const DEFAULT_CHUNKSIZE=5;

class CloneDetector {
    #myChunkSize = process.env.CHUNKSIZE || DEFAULT_CHUNKSIZE;
    #myFileStore = FileStorage.getInstance();

    constructor() {
    }

    // Private Methods
    // --------------------
    #filterLines(file) {
        let lines = file.contents.split('\n');
        let inMultiLineComment = false;
        file.lines=[];

        for (let i = 0; i < lines.length; i++) {
            let line = lines[i];

            if ( inMultiLineComment ) {
                if ( -1 != line.search(closeMultiLineComment) ) {
                    line = line.replace(closeMultiLineComment, '');
                    inMultiLineComment = false;
                } else {
                    line = '';
                }
            }

            line = line.replace(emptyLine, '');
            line = line.replace(oneLineComment, '');
            line = line.replace(oneLineMultiLineComment, '');
            
            if ( -1 != line.search(openMultiLineComment) ) {
                line = line.replace(openMultiLineComment, '');
                inMultiLineComment = true;
            }

            file.lines.push( new SourceLine(i+1, line.trim()) );
        }
       
        return file;
    }

    #getContentLines(file) {
        return file.lines.filter( line => line.hasContent() );        
    }


    #chunkify(file) {
        let chunkSize = this.#myChunkSize;
        let lines = this.#getContentLines(file);
        file.chunks=[];

        for (let i = 0; i <= lines.length-chunkSize; i++) {
            let chunk = lines.slice(i, i+chunkSize);
            file.chunks.push(chunk);
        }
        return file;
    }
    
    #chunkMatch(first, second) {
        let match = true;

        if (first.length != second.length) { match = false; }
        for (let idx=0; idx < first.length; idx++) {
            if (!first[idx].equals(second[idx])) { match = false; }
        }

        return match;
    }

    #filterCloneCandidates(file, compareFile) {
        // TODO
        // For each chunk in file.chunks, find all #chunkMatch() in compareFile.chunks
        // For each matching chunk, create a new Clone.
        // Store the resulting (flat) array in file.instances.
        // 
        // TIP 1: Array.filter to find a set of matches, Array.map to return a new array with modified objects.
        // TIP 2: You can daisy-chain calls to filter().map().filter().flat() etc.
        // TIP 3: Remember that file.instances may have already been created, so only append to it.
        //
        // Return: file, including file.instances which is an array of Clone objects (or an empty array).
        //

        file.instances = file.instances || [];

        // For each chunk in file, find matching chunks in compareFile
        const newInstances = file.chunks
            .map( chunk => {
                // Find all matching chunks in compareFile
                const matches = compareFile.chunks.filter( c2 => this.#chunkMatch(chunk, c2) );
                // Map matches to Clone objects
                return matches.map( m => new Clone(file.name, compareFile.name, chunk, m) );
            })
            .flat();

        file.instances = file.instances.concat(newInstances);
        return file;
    }
     
    #expandCloneCandidates(file) {
        // TODO
        // For each Clone in file.instances, try to expand it with every other Clone
        // (using Clone::maybeExpandWith(), which returns true if it could expand)
        // 
        // Comment: This should be doable with a reduce:
        //          For every new element, check if it overlaps any element in the accumulator.
        //          If it does, expand the element in the accumulator. If it doesn't, add it to the accumulator.
        //
        // ASSUME: As long as you traverse the array file.instances in the "normal" order, only forward expansion is necessary.
        // 
        // Return: file, with file.instances only including Clones that have been expanded as much as they can,
        //         and not any of the Clones used during that expansion.
        //

        const acc = [];
        for (let clone of file.instances || []) {
            // try to expand against existing accumulated clones
            let expanded = false;
            for (let a of acc) {
                if (a.sourceName === clone.sourceName && a.maybeExpandWith(clone)) {
                    expanded = true;
                    break;
                }
            }
            if (!expanded) acc.push(clone);
        }

        file.instances = acc;
        return file;
    }
    
    #consolidateClones(file) {
        // TODO
        // For each clone, accumulate it into an array if it is new
        // If it isn't new, update the existing clone to include this one too
        // using Clone::addTarget()
        // 
        // TIP 1: Array.reduce() with an empty array as start value.
        //        Push not-seen-before clones into the accumulator
        // TIP 2: There should only be one match in the accumulator
        //        so Array.find() and Clone::equals() will do nicely.
        //
        // Return: file, with file.instances containing unique Clone objects that may contain several targets
        //

        const acc = [];
        for (let clone of file.instances || []) {
            let existing = acc.find( c => c.equals(clone) );
            if (existing) {
                existing.addTarget(clone);
            } else {
                acc.push(clone);
            }
        }
        file.instances = acc;
        return file;
    }
    

    // Public Processing Steps
    // --------------------
    preprocess(file) {
        return new Promise( (resolve, reject) => {
            if (!file.name.endsWith('.java') ) {
                reject(file.name + ' is not a java file. Discarding.');
            } else if(this.#myFileStore.isFileProcessed(file.name)) {
                reject(file.name + ' has already been processed.');
            } else {
                resolve(file);
            }
        });
    }

    async transform(file) {
        file = this.#filterLines(file);
        file = this.#chunkify(file);
        // Index all chunks for quick lookup (persisted)
        const idx = ChunkIndex.getInstance();
        for (let chunk of file.chunks) {
            // don't await every call serially to avoid slowdown; fire-and-forget is acceptable here
            idx.add(file.name, chunk[0].lineNumber, chunk).catch && idx.add(file.name, chunk[0].lineNumber, chunk);
        }
        return file;
    }

    async matchDetect(file) {
        // Use chunk index to find candidates rather than scanning all files
        const idx = ChunkIndex.getInstance();
        file.instances = file.instances || [];

        for (let chunk of file.chunks) {
            const matches = await idx.lookup(chunk);
            for (let m of matches) {
                if (m.name === file.name) continue;
                // m.chunk contains the stored chunk string (joined lines)
                if (!m.chunk) {
                    // missing chunk content in index entry; skip
                    continue;
                }
                const targetLines = m.chunk.split('\n').map( (c, i) => new SourceLine(m.startLine + i, c) );
                file.instances.push( new Clone(file.name, m.name, chunk, targetLines) );
            }
        }

        file = this.#expandCloneCandidates(file);
        file = this.#consolidateClones(file);
        return file;
    }

    pruneFile(file) {
        delete file.lines;
        delete file.instances;
        return file;
    }
    
    storeFile(file) {
        this.#myFileStore.storeFile(this.pruneFile(file));
        return file;
    }

    get numberOfProcessedFiles() { return this.#myFileStore.numberOfFiles; }
}

module.exports = CloneDetector;
