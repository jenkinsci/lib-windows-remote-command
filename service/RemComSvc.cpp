/*
	Copyright (c) 2006 Talha Tariq [ talha.tariq@gmail.com ] 
	All rights are reserved.

	Permission to use, copy, modify, and distribute this software 
	for any purpose and without any fee is hereby granted, 
	provided this notice is included in its entirety in the 
	documentation and in the source files.

	This software and any related documentation is provided "as is" 
	without any warranty of any kind, either express or implied, 
	including, without limitation, the implied warranties of 
	merchantability or fitness for a particular purpose. The entire 
	risk arising out of use or performance of the software remains 
	with you. 

 	$Author:	Talha Tariq [ talha.tariq@gmail.com ] 
				uses some code from xCmd by Zoltan Csizmadia
	$Revision:	Talha Tariq [ talha.tariq@gmail.com ] 	
	$Date: 2006/10/03 09:00:00 $ 		
	$Version History: $			- 
	$TODO:						- Implement Delete Service
	$Description: $				- RemCom Service is contained in the parent binary as a local resource which is extracted at runtime from itself
								  pushed to admin$, installed to the remote service control manager which interacts remotely for local process invocation
								
	$Workfile: $				- RemComSvc.cpp
 */

#include <windows.h>
#include <tchar.h>
#include <stdio.h>
#include <stdlib.h>
#include <aclapi.h>
#include <winsvc.h>
#include <process.h>
#include "RemComSvc.h"
#include "../RemCom.h"

void	CommunicationPoolThread(PVOID);
void	CommunicationPipeThreadProc(PVOID);
DWORD	Execute(HANDLE, RemComMessage*, DWORD*);

LONG	dwSvcPipeInstanceCount = 0;

// Service "main" function
void _ServiceMain( void* )
{
   // Start CommunicationPoolThread, which handles the incoming instances
   _beginthread( CommunicationPoolThread, 0, NULL );

   // Waiting for stop the service
   while( WaitForSingleObject( hStopServiceEvent, 10 ) != WAIT_OBJECT_0 )
   {
   }
   
   // Let's delete itself, after the service stopped
   DeleteSvc();

   CloseHandle( hStopServiceEvent );
}

// Communicaton Thread Pool, handles the incoming RemCom.exe requests
void CommunicationPoolThread(PVOID)
{
/*
		SID_IDENTIFIER_AUTHORITY SIDOwner = SECURITY_CREATOR_SID_AUTHORITY;
		PSID sid;
		AllocateAndInitializeSid(&SIDOwner,1,
			SECURITY_CREATOR_OWNER_RID, 0,0,0,0,0,0,0,&sid);

		EXPLICIT_ACCESS ea;
		ZeroMemory(&ea,sizeof(EXPLICIT_ACCESS));
		ea.grfAccessPermissions = GENERIC_ALL;
		ea.grfAccessMode = SET_ACCESS;
		ea.grfInheritance = NO_INHERITANCE;
		ea.Trustee.TrusteeForm = TRUSTEE_IS_SID;
		ea.Trustee.TrusteeType = TRUSTEE_IS_WELL_KNOWN_GROUP;
		ea.Trustee.ptstrName = (LPTSTR)sid;

		PACL pACL = NULL;
		SetEntriesInAcl(1, &ea, NULL, &pACL);
*/
    for (;;)
    {

        SECURITY_ATTRIBUTES SecAttrib = {0};
        SECURITY_DESCRIPTOR SecDesc;

		InitializeSecurityDescriptor(&SecDesc, SECURITY_DESCRIPTOR_REVISION);
        SetSecurityDescriptorDacl(&SecDesc, TRUE, NULL/*pACL*/, TRUE);

        SecAttrib.nLength = sizeof(SECURITY_ATTRIBUTES);
        SecAttrib.lpSecurityDescriptor = &SecDesc;;
        SecAttrib.bInheritHandle = TRUE;

        // Create communication pipe
        HANDLE hPipe = CreateNamedPipe(
            _T("\\\\.\\pipe\\")RemComCOMM, 
            PIPE_ACCESS_DUPLEX, 
            PIPE_WAIT, 
            PIPE_UNLIMITED_INSTANCES,
            0,
            0,
            (DWORD)-1,
            &SecAttrib);

        if ( hPipe != NULL )
        {
            // Waiting for client to connect to this pipe
            ConnectNamedPipe( hPipe, NULL );
            _beginthread( CommunicationPipeThreadProc, 0, (void*)hPipe);
        }
    }
}

BOOL ReadFully(HANDLE h, LPVOID buf, DWORD sz) {
	DWORD nRead;
	BYTE* p = reinterpret_cast<BYTE*>(buf);
	while (sz>0) {
		if (!::ReadFile(h,p,sz,&nRead,NULL) || nRead==0)
			return FALSE;
		p+= nRead;
		sz -= nRead;
	}
	return TRUE;
}

// Handles a client
void CommunicationPipeThreadProc( void* pParam )
{
   HANDLE hPipe = (HANDLE)pParam;

   RemComMessage msg;
   RemComResponse response;

   DWORD dwWritten;

   // Increment instance counter 
   InterlockedIncrement( &dwSvcPipeInstanceCount );

   ::ZeroMemory( &response, sizeof(response) );

   // Waiting for communication message from client
   if (!ReadFully( hPipe, &msg, sizeof(msg)))
	   goto cleanup;

   // Execute the requested command
   response.dwErrorCode  = Execute( hPipe, &msg, &response.dwReturnCode );
   
   // Send back the response message (client is waiting for this response)
   if ( !WriteFile( hPipe, &response, sizeof(response), &dwWritten, NULL ) || dwWritten == 0 )
      goto cleanup;

cleanup:

   // DisconnectNamedPipe( hPipe );
   CloseHandle( hPipe );

   // Decrement instance counter 
   InterlockedDecrement( &dwSvcPipeInstanceCount );

   // If this was the last client, let's stop ourself
   if ( dwSvcPipeInstanceCount == 0 )
      SetEvent( hStopServiceEvent );
     
}

struct CopyThreadParams {
	HANDLE hPipe;
	HANDLE hStdin;
};

void CopyStream(PVOID p) {
	CopyThreadParams* data = (CopyThreadParams*)p;
	while (true) {
		DWORD len = 0;
		if (!ReadFully(data->hPipe,&len,sizeof(DWORD)))
			break;

		if (len==0)	break;	// EOF

		BYTE* buf = (BYTE*)malloc(len);
		if (!ReadFully(data->hPipe,buf,len))
			break;

		DWORD dwWritten;
		WriteFile(data->hStdin, buf, len, &dwWritten, NULL);
		free(buf);
		// repeeat
	}

	CloseHandle(data->hStdin);
	delete data;
	return;
}

// Execute the requested client command
DWORD Execute( HANDLE hPipe, RemComMessage* pMsg, DWORD* pReturnCode )
{
	PROCESS_INFORMATION pi;

	STARTUPINFO si;
	::ZeroMemory( &si, sizeof(si) );
	si.cb = sizeof(si);

	SECURITY_ATTRIBUTES saAttr;
	saAttr.nLength = sizeof(SECURITY_ATTRIBUTES);
	saAttr.bInheritHandle = true;
	saAttr.lpSecurityDescriptor = NULL;
	HANDLE hStdoutRead = INVALID_HANDLE_VALUE, hStdoutWrite = INVALID_HANDLE_VALUE;
	HANDLE hStdinRead = INVALID_HANDLE_VALUE, hStdinWrite = INVALID_HANDLE_VALUE;

	if (!CreatePipe(&hStdoutRead, &hStdoutWrite, &saAttr, 0))
		return 20000+GetLastError();

	if (!SetHandleInformation(hStdoutRead, HANDLE_FLAG_INHERIT, 0))
		return 30000+GetLastError();	// don't inherit the reader end

	if (!CreatePipe(&hStdinRead, &hStdinWrite, &saAttr, 0))
		return 40000+GetLastError();

	if (!SetHandleInformation(hStdinWrite, HANDLE_FLAG_INHERIT, 0))
		return 50000+GetLastError();	// don't inherit the reader end

	si.hStdInput = hStdinRead;
	si.hStdOutput = hStdoutWrite;
	si.hStdError = hStdoutWrite;
	si.dwFlags |= STARTF_USESTDHANDLES;
	
	*pReturnCode = 0;

	// Initializes command
	// cmd.exe /c /q allows us to execute internal dos commands too.
	TCHAR szCommand[_MAX_PATH];
	_stprintf_s( szCommand, sizeof(szCommand), _T("cmd.exe /q /c \"%s\""), pMsg->szCommand );

	if (!::ImpersonateNamedPipeClient(hPipe))
		return 60000+GetLastError();

	HANDLE hImpersonationToken,hPrimaryToken;
	if (!::OpenThreadToken(::GetCurrentThread(),TOKEN_ALL_ACCESS,TRUE,&hImpersonationToken))
		return 70000+GetLastError();

	if (!::DuplicateTokenEx(hImpersonationToken,MAXIMUM_ALLOWED,0,
		SecurityIdentification, TokenPrimary,&hPrimaryToken))
		return 80000+GetLastError();
	CloseHandle(hImpersonationToken);
	RevertToSelf();

   // Start the requested process
	if ( CreateProcessAsUser(hPrimaryToken, 
         NULL, 
         szCommand, 
         NULL,
         NULL, 
         TRUE,
         pMsg->dwPriority | CREATE_NO_WINDOW,
         NULL, 
         pMsg->szWorkingDir[0] != _T('\0') ? pMsg->szWorkingDir : NULL, 
         &si, 
         &pi ) )
   {
		HANDLE hProcess = pi.hProcess;
		// these handles are meant only for the child process, otherwise we'll block forever
		CloseHandle(hStdinRead);
		CloseHandle(hStdoutWrite);

		// pump the stdin from the client to the child process
		// in a different thread
		CopyThreadParams* ctp = new CopyThreadParams();
		ctp->hPipe = hPipe;
		ctp->hStdin = hStdinWrite;
		_beginthread(CopyStream,0,ctp);

		// feed the output from the child process to the client
		while (true) {
			byte buf[1024];
			DWORD dwRead = 0, dwWritten;

			if (!::ReadFile(hStdoutRead,buf+4,sizeof(buf)-4,&dwRead,NULL) || dwRead==0)
				break;

			*((DWORD*)buf) = dwRead;
			::WriteFile(hPipe, buf, sizeof(DWORD)+dwRead, &dwWritten, NULL);
		}

		*pReturnCode = 0;

		// Waiting for process to terminate
        WaitForSingleObject( hProcess, INFINITE );
        GetExitCodeProcess( hProcess, pReturnCode );
		return 0;
	} else {
		// error code 1314 here means the user of this process
		// doesn't have the privilege to call CreateProcessAsUser.
		// when this is run as a service, the LOCAL_SYSTEM user
		// has a privilege to do it, but if you run this under
		// the debugger, your user account might not have that privilege.
		//
		// to fix that, go to control panel / administartive tools / local security policy
		// user rights / replace process token level
		// and make sure your user is in.
		// once the above is set, the system needs to be restarted.
		return 10000+GetLastError();
	}
}
