class NonogramSolver {
    constructor(size, rowClues, colClues) {
        this.size = size;
        this.rowClues = rowClues;
        this.colClues = colClues;
        // 0 = unknown, 1 = filled, -1 = empty
        this.grid = Array(size).fill(null).map(() => Array(size).fill(0));
    }

    solve() {
        return this.backtrack();
    }

    backtrack() {
        // 1. Run logical deduction as far as possible
        const isValid = this.logicalSolve();
        if (!isValid) return false; 
        if (this.isSolved()) return true;

        // 2. Find the first unknown cell to guess
        let guessR = -1, guessC = -1;
        outer: for (let r = 0; r < this.size; r++) {
            for (let c = 0; c < this.size; c++) {
                if (this.grid[r][c] === 0) {
                    guessR = r; guessC = c;
                    break outer;
                }
            }
        }

        if (guessR === -1) return this.isSolved();

        // 3. Save board state before making a guess
        const snapshot = this.grid.map(row => [...row]);

        // 4. Guess Filled (1)
        this.grid[guessR][guessC] = 1;
        if (this.backtrack()) return true;

        // 5. Revert and Guess Empty (-1)
        for (let r = 0; r < this.size; r++) {
            for (let c = 0; c < this.size; c++) this.grid[r][c] = snapshot[r][c];
        }

        this.grid[guessR][guessC] = -1;
        if (this.backtrack()) return true;

        // 6. Both guesses failed, revert state completely
        for (let r = 0; r < this.size; r++) {
            for (let c = 0; c < this.size; c++) this.grid[r][c] = snapshot[r][c];
        }
        return false;
    }

    logicalSolve() {
        let changed = true;
        while (changed) {
            changed = false;
            for (let r = 0; r < this.size; r++) {
                let result = this.solveLine(this.grid[r], this.rowClues[r]);
                if (result === -1) return false; 
                if (result === 1) changed = true;
            }
            for (let c = 0; c < this.size; c++) {
                const colData = this.grid.map(row => row[c]);
                let result = this.solveLine(colData, this.colClues[c]);
                if (result === -1) return false; 
                if (result === 1) {
                    changed = true;
                    for (let r = 0; r < this.size; r++) this.grid[r][c] = colData[r];
                }
            }
        }
        return true;
    }

    solveLine(line, clues) {
        if (!line.includes(0)) {
            let actualClues = [];
            let count = 0;
            for (let val of line) {
                if (val === 1) count++;
                else if (count > 0) { actualClues.push(count); count = 0; }
            }
            if (count > 0) actualClues.push(count);
            if (actualClues.length === 0) actualClues = [0];
            
            const isValid = actualClues.length === clues.length && actualClues.every((c, i) => c === clues[i]);
            return isValid ? 0 : -1; 
        }

        const validPerms = this.getLinePermutations(clues, this.size, line);
        if (validPerms.length === 0) return -1; 

        let lineChanged = false;
        for (let i = 0; i < this.size; i++) {
            if (line[i] !== 0) continue; 
            
            let allFilled = validPerms.every(p => p[i] === 1);
            let allEmpty = validPerms.every(p => p[i] === -1);
            
            if (allFilled) {
                line[i] = 1;
                lineChanged = true;
            } else if (allEmpty) {
                line[i] = -1;
                lineChanged = true;
            }
        }
        return lineChanged ? 1 : 0;
    }

    getLinePermutations(clues, length, currentLine) {
        if (clues.length === 1 && clues[0] === 0) {
            let p = Array(length).fill(-1);
            return this.isValidMatch(p, currentLine) ? [p] : [];
        }

        let perms = [];
        let numBlocks = clues.length;
        let minSpaces = numBlocks - 1;
        let blocksLen = clues.reduce((a, b) => a + b, 0);
        let slack = length - blocksLen - minSpaces;
        if (slack < 0) return [];

        const buildAndCheck = (spaces) => {
            let p = [];
            for (let i = 0; i < numBlocks; i++) {
                let numSpaces = spaces[i] + (i > 0 ? 1 : 0);
                for(let s=0; s<numSpaces; s++) p.push(-1);
                for(let b=0; b<clues[i]; b++) p.push(1);
            }
            for(let s=0; s<spaces[numBlocks]; s++) p.push(-1);
            
            if (this.isValidMatch(p, currentLine)) perms.push(p);
        };

        const distribute = (s, buckets, currentDist) => {
            if (buckets === 1) return buildAndCheck([...currentDist, s]);
            for (let i = 0; i <= s; i++) distribute(s - i, buckets - 1, [...currentDist, i]);
        };

        distribute(slack, numBlocks + 1, []);
        return perms;
    }

    isValidMatch(perm, currentLine) {
        for (let i = 0; i < perm.length; i++) {
            if (currentLine[i] !== 0 && currentLine[i] !== perm[i]) return false;
        }
        return true;
    }

    isSolved() {
        return this.grid.every(row => row.every(cell => cell !== 0));
    }
}