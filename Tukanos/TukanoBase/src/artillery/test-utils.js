'use strict';

/***
 * Exported functions to be used in the testing scripts.
 */
module.exports = {
  printStatus: printStatus,
  uploadRandomizedUser,
  processRegisterReply,
  uploadUserWithCount,
  resetCount,
  incrementCounter
}


const fs = require('fs') // Needed for access to blobs.

var registeredUsers = []
var images = []
var userNameCount = 0;
// All endpoints starting with the following prefixes will be aggregated in the same for the statistics
var statsPrefix = [ ["/rest/media/","GET"],
			["/rest/media","POST"]
	]

// Function used to compress statistics
global.myProcessEndpoint = function( str, method) {
	var i = 0;
	for( i = 0; i < statsPrefix.length; i++) {
		if( str.startsWith( statsPrefix[i][0]) && method == statsPrefix[i][1])
			return method + ":" + statsPrefix[i][0];
	}
	return method + ":" + str;
}

// Returns a random username constructed from lowercase letters.
function randomUsername(char_limit){
    const letters = 'abcdefghijklmnopqrstuvwxyz';
    let username = '';
    let num_chars = Math.floor(Math.random() * char_limit);
    for (let i = 0; i < num_chars; i++) {
        username += letters[Math.floor(Math.random() * letters.length)];
    }
    return username;
}


// Returns a random password, drawn from printable ASCII characters
function randomPassword(pass_len){
    const skip_value = 33;
    const lim_values = 94;
    
    let password = '';
    let num_chars = Math.floor(Math.random() * pass_len);
    for (let i = 0; i < pass_len; i++) {
        let chosen_char =  Math.floor(Math.random() * lim_values) + skip_value;
        if (chosen_char == "'" || chosen_char == '"')
            i -= 1;
        else
            password += chosen_char
    }
    return password;
}

/**
 * Process reply of the user registration.
 */
function processRegisterReply(requestParams, response, context, ee, next) {
    if( typeof response.body !== 'undefined' && response.body.length > 0) {
        registeredUsers.push(response.body);
    }
    return next();
} 

/**
 * Register a random user.
 */

function uploadRandomizedUser(requestParams, context, ee, next) {
    let username = randomUsername(10);
    let pword = randomPassword(15);
    let email = username + "@campus.fct.unl.pt";
    let displayName = username;
    
    const user = {
        userId: username,
        pwd: pword,
        email: email,
        displayName: username
    };
    requestParams.body = JSON.stringify(user);
    return next();
}

function uploadUserWithCount(requestParams, context, ee, next){
    let username = "name" + userNameCount;
    userNameCount++;
    let pword = "easypass"
    let email = username + "@campus.fct.unl.pt";
    let displayName = username;

    const user = {
        userId: username,
        pwd: pword,
        email: email,
        displayName: username
    };
    requestParams.body = JSON.stringify(user);
    return next();
}

function resetCount(requestParams, context, ee, next) {
    userNameCount = 0;
}

function incrementCounter(requestParams, context, ee, next){
    if(!context.counter) {
        context.counter = 0;
    }else{
        context.counter += 1;
    }
    return next();
}

/*function printStatus (requestParams, response, context, ee, next) {
    // Check if the response is JSON
    if (response.body && typeof response.body === 'object') {
        // Pretty-print JSON with 2 spaces indentation
        const prettyPrinted = JSON.stringify(response.body, null, 2);
        console.log(prettyPrinted.split('\n').join('\n')); // Forces new lines for readability
    } else {
        // Print as is if it's not an object
        console.log(response.body);
    }
    return next();
}*/

function printStatus(requestParams, response, context, ee, next) {
    try {
        // Check if response body exists
        if (response.body) {
            let responseBody;

            // If body is already parsed as JSON object
            if (typeof response.body === 'object') {
                responseBody = response.body;
            } else {
                // Attempt to parse if body is a JSON string
                responseBody = JSON.stringify(response.body);
            }

            // Pretty-print JSON with 2 spaces indentation
            const prettyPrinted = JSON.stringify(responseBody, null, 2);
            console.log(prettyPrinted);
        } else {
            console.log("Response body is empty or undefined.");
        }
    } catch (error) {
        // If parsing fails or another error occurs, log the error and response body
        console.error("Error printing response:", error);
        console.log("Raw response body:", response.body);
    }

    return next();
}