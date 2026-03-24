const express = require('express');
const formidable = require('formidable');
const fs = require('fs/promises');
const app = express();
const PORT = 3000;

const Timer = require('./Timer');
const CloneDetector = require('./CloneDetector');
const CloneStorage = require('./CloneStorage');
const FileStorage = require('./FileStorage');

// Global error handlers to capture crashes
process.on('uncaughtException', (err) => {
    console.error('uncaughtException', err && err.stack ? err.stack : err);
});
process.on('unhandledRejection', (reason, p) => {
    console.error('unhandledRejection', reason);
});


// Express and Formidable stuff to receive a file for further processing
// Create a new Formidable instance per request to avoid adding listeners to a single instance
app.post('/', fileReceiver );
function fileReceiver(req, res, next) {
    const form = formidable({multiples:false});
    form.parse(req, (err, fields, files) => {
        if (err) {
            console.log('Form parse error:', err);
            return res.status(400).end('parse error');
        }

        const upload = (files && (files.data || files.file || files.upload));
        if (!upload || !upload.filepath) {
            console.log('No uploaded file found in request. fields:', fields, 'files:', Object.keys(files || {}));
            return res.status(400).end('no file');
        }

        fs.readFile(upload.filepath, { encoding: 'utf8' })
            .then( data => { return processFile(fields.name || upload.originalFilename || upload.newFilename || 'unknown', data); })
            .catch( e => { console.log(e); });
    });
    return res.end('');
}

app.get('/', viewClones );

const server = app.listen(PORT, () => { console.log('Listening for files on port', PORT); });


// Page generation for viewing current progress
// --------------------
function getStatistics() {
    let cloneStore = CloneStorage.getInstance();
    let fileStore = FileStorage.getInstance();
    let output = 'Processed ' + fileStore.numberOfFiles + ' files containing ' + cloneStore.numberOfClones + ' clones.'
    return output;
}

function lastFileTimersHTML() {
    if (!lastFile) return '';
    output = '<p>Timers for last file processed:</p>\n<ul>\n'
    let timers = Timer.getTimers(lastFile);
    for (t in timers) {
        output += '<li>' + t + ': ' + (timers[t] / (1000n)) + ' µs\n'
    }
    output += '</ul>\n';
    return output;
}

function listClonesHTML() {
    let cloneStore = CloneStorage.getInstance();
    let output = '';

    cloneStore.clones.forEach( clone => {
        output += '<hr>\n';
        output += '<h2>Source File: ' + clone.sourceName + '</h2>\n';
        output += '<p>Starting at line: ' + clone.sourceStart + ' , ending at line: ' + clone.sourceEnd + '</p>\n';
        output += '<ul>';
        clone.targets.forEach( target => {
            output += '<li>Found in ' + target.name + ' starting at line ' + target.startLine + '\n';            
        });
        output += '</ul>\n'
        output += '<h3>Contents:</h3>\n<pre><code>\n';
        output += clone.originalCode;
        output += '</code></pre>\n';
    });

    return output;
}

function listProcessedFilesHTML() {
    let fs = FileStorage.getInstance();
    let output = '<HR>\n<H2>Processed Files</H2>\n'
    output += fs.filenames.reduce( (out, name) => {
        out += '<li>' + name + '\n';
        return out;
    }, '<ul>\n');
    output += '</ul>\n';
    return output;
}

function viewClones(req, res, next) {
    let page='<HTML><HEAD><TITLE>CodeStream Clone Detector</TITLE></HEAD>\n';
    page += '<BODY><H1>CodeStream Clone Detector</H1>\n';
    page += '<P>' + getStatistics() + '</P>\n';
    page += lastFileTimersHTML() + '\n';
    page += listClonesHTML() + '\n';
    page += listProcessedFilesHTML() + '\n';
    page += '</BODY></HTML>';
    res.send(page);
}

// Some helper functions
// --------------------
// PASS is used to insert functions in a Promise stream and pass on all input parameters untouched.
PASS = fn => d => {
    try {
        fn(d);
        return d;
    } catch (e) {
        throw e;
    }
};

const STATS_FREQ = 100;
const URL = process.env.URL || 'http://localhost:8080/';
var lastFile = null;
// In-memory timers history. Each entry: { name, numLines, timers }
const TIMERS_HISTORY_MAX = 1000;
const timersHistory = [];

function maybePrintStatistics(file, cloneDetector, cloneStore) {
    if (0 == cloneDetector.numberOfProcessedFiles % STATS_FREQ) {
        console.log('Processed', cloneDetector.numberOfProcessedFiles, 'files and found', cloneStore.numberOfClones, 'clones.');
        let timers = Timer.getTimers(file);
        let str = 'Timers for last file processed: ';
        for (t in timers) {
            str += t + ': ' + (timers[t] / (1000n)) + ' µs '
        }
        console.log(str);
        console.log('List of found clones available at', URL);
    }

    return file;
}

// Processing of the file
// --------------------
async function processFile(filename, contents) {
    let cd = new CloneDetector();
    let cloneStore = CloneStorage.getInstance();

    try {
        let file = { name: filename, contents };
        file = Timer.startTimer(file, 'total');
        file = await cd.preprocess(file);
        file = await cd.transform(file);

        file = Timer.startTimer(file, 'match');
        file = await cd.matchDetect(file);
        file = cloneStore.storeClones(file);
        file = Timer.endTimer(file, 'match');

        file = cd.storeFile(file);
        file = Timer.endTimer(file, 'total');
        lastFile = file;
        maybePrintStatistics(file, cd, cloneStore);
        storeTimersPASS(file);
    } catch (e) {
        console.log(e);
    }
}

// Store timers into history for later inspection
function storeTimersHistory(file) {
    if (!file) return;
    const entry = {
        name: file.name,
        numLines: file.contents ? file.contents.split('\n').length : 0,
        timers: Timer.getTimers(file) || {}
    };

    timersHistory.push(entry);
    if (timersHistory.length > TIMERS_HISTORY_MAX) timersHistory.shift();
}

// New endpoint to view more detailed timing statistics
app.get('/timers', (req, res) => {
    // query param n for last n entries, default 100
    const n = Math.min(1000, parseInt(req.query.n) || 100);
    const slice = timersHistory.slice(-n);

    // compute averages for each timer key
    const sums = {};
    let count = 0;
    for (let e of slice) {
        count++;
        for (let k in e.timers) {
            sums[k] = (sums[k] || 0n) + BigInt(e.timers[k] || 0n);
        }
    }

    const avgs = {};
    for (let k in sums) {
        avgs[k] = Number(sums[k] / BigInt(Math.max(1, count)));
    }

    let page = '<HTML><HEAD><TITLE>Timing statistics</TITLE></HEAD>\n<BODY><H1>Timing statistics</H1>';
    page += '<p>Showing last ' + slice.length + ' entries</p>';
    page += '<h2>Averages (nanoseconds)</h2><ul>';
    for (let k in avgs) page += '<li>' + k + ': ' + avgs[k] + '</li>';
    page += '</ul>';

    page += '<h2>Entries</h2>';
    for (let e of slice.reverse()) {
        page += '<hr><h3>' + e.name + ' (' + e.numLines + ' lines)</h3><ul>';
        for (let k in e.timers) {
            page += '<li>' + k + ': ' + e.timers[k] + '</li>';
        }
        page += '</ul>';
    }

    page += '</BODY></HTML>';
    res.send(page);
});

// Hook storing timers after clone storage and file store
// We use the PASS helper to store timers
const storeTimersPASS = (file) => { storeTimersHistory(file); return file; };


/*
1. Preprocessing: Remove uninteresting code, determine source and comparison units/granularities
2. Transformation: One or more extraction and/or transformation techniques are applied to the preprocessed code to obtain an intermediate representation of the code.
3. Match Detection: Transformed units (and/or metrics for those units) are compared to find similar source units.
4. Formatting: Locations of identified clones in the transformed units are mapped to the original code base by file location and line number.
5. Post-Processing and Filtering: Visualisation of clones and manual analysis to filter out false positives
6. Aggregation: Clone pairs are aggregated to form clone classes or families, in order to reduce the amount of data and facilitate analysis.
*/
